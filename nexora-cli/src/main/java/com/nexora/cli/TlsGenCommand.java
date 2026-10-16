package com.nexora.cli;

import com.nexora.api.auth.SecureFiles;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "tls-gen",
        // description loaded from help/tls-gen.help at startup via HelpLoader
        mixinStandardHelpOptions = true)
public class TlsGenCommand implements Callable<Integer> {

    static final String CA_FILE = "ca.pem";
    static final String CERTIFICATE_FILE = "server.pem";
    static final String KEY_FILE = "server-key.pem";

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;

    @Spec
    private CommandSpec spec;

    /** Tests pin the clock; issuing uses it for the validity period and the CA's name. */
    Clock clock = Clock.systemUTC();

    @Option(
            names = {"--out"},
            defaultValue = "./certs",
            description = "Directory to write " + CA_FILE + ", " + CERTIFICATE_FILE + " and " + KEY_FILE
                    + " to. Default: ${DEFAULT-VALUE}")
    private Path out;

    @Option(
            names = {"--host"},
            description = "Extra host name or IP address the certificate is valid for, besides localhost,"
                    + " 127.0.0.1 and ::1. Repeat for more.")
    private List<String> hosts = new ArrayList<>();

    @Option(
            names = {"--days"},
            defaultValue = "" + DevCertificates.DEFAULT_VALID_DAYS,
            description = "Days the certificates stay valid, at most " + DevCertificates.MAX_VALID_DAYS
                    + ". Default: ${DEFAULT-VALUE}")
    private int days;

    @Option(
            names = {"--force"},
            description = "Replace certificate files already in --out.")
    private boolean force;

    @Override
    public Integer call() {
        PrintWriter err = spec.commandLine().getErr();
        Path ca = out.resolve(CA_FILE);
        Path certificate = out.resolve(CERTIFICATE_FILE);
        Path key = out.resolve(KEY_FILE);
        List<Path> existing =
                List.of(ca, certificate, key).stream().filter(Files::exists).toList();
        if (!existing.isEmpty() && !force) {
            err.println("Error: " + existing + " already exist(s). Pass --force to replace them; a browser or"
                    + " trust store that trusted the old " + CA_FILE + " will then need the new one.");
            return EXIT_USAGE;
        }

        DevCertificates.Issued issued;
        List<String> names;
        try {
            names = DevCertificates.withDefaultHosts(hosts);
            issued = DevCertificates.issue(names, days, clock.instant());
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_USAGE;
        }

        try {
            // All three are owner-only; ca.pem holds no secret, but it lands next to the key.
            SecureFiles.writeOwnerOnly(key, PemFiles.privateKeyPem(issued.serverKey()));
            SecureFiles.writeOwnerOnly(certificate, PemFiles.certificatePem(issued.serverCertificate()));
            SecureFiles.writeOwnerOnly(ca, PemFiles.certificatePem(issued.caCertificate()));
        } catch (IOException e) {
            err.println("Error: could not write the certificates to " + out + ": " + e.getMessage());
            return EXIT_FAILED;
        }
        printNextSteps(spec.commandLine().getOut(), names, ca, certificate, key, issued);
        return EXIT_OK;
    }

    private void printNextSteps(
            PrintWriter out, List<String> names, Path ca, Path certificate, Path key, DevCertificates.Issued issued) {
        out.println("Development certificates written to "
                + this.out.toAbsolutePath().normalize());
        out.println("  " + CA_FILE + "          certificate authority to trust (the only file to share)");
        out.println("  " + CERTIFICATE_FILE + "      server certificate for: " + String.join(", ", names));
        out.println("  " + KEY_FILE + "  server private key; keep it private");
        out.println("Valid until " + issued.serverCertificate().getNotAfter().toInstant()
                + ". The CA's own key was not kept, so nothing else can be signed with it.");
        out.println();
        out.println("Serve HTTPS:");
        out.println("  nexora observe --tls-cert " + certificate + " --tls-key " + key);
        out.println("Call it with curl:");
        out.println("  curl --cacert " + ca + " https://localhost:9464/health/live");
        out.println("Use it from the CLI:");
        out.println("  nexora login --server https://localhost:9464 --cacert " + ca);
        out.println("For a browser, import " + CA_FILE + " as a trusted certificate authority, for example:");
        out.println("  macOS:   sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain "
                + ca);
        out.println("  Linux:   sudo cp " + ca + " /usr/local/share/ca-certificates/nexora-dev.crt"
                + " && sudo update-ca-certificates");
        out.println(
                "  Firefox: Settings > Privacy & Security > Certificates > View Certificates > Authorities > Import");
        out.println("Do not use these certificates in production; use one from your CA or Let's Encrypt.");
    }
}
