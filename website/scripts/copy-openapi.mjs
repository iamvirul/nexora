// Copies the observe server's OpenAPI document into the site, so GitHub Pages publishes the same
// file the server serves at /openapi.yaml. The document lives once, in nexora-cli; the copy under
// static/api is generated on every start and build and is not committed.
import {copyFileSync, existsSync, mkdirSync, readFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const websiteDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(websiteDir, '../nexora-cli/src/main/resources/openapi.yaml');
const target = resolve(websiteDir, 'static/api/openapi.yaml');

if (!existsSync(source)) {
  console.error(`copy-openapi: ${source} not found; the site needs the observe server's OpenAPI document.`);
  process.exit(1);
}

const version = /^\s{2}version:\s*(\S+)\s*$/m.exec(readFileSync(source, 'utf8'))?.[1];
if (!version) {
  console.error(`copy-openapi: no info.version in ${source}.`);
  process.exit(1);
}

mkdirSync(dirname(target), {recursive: true});
copyFileSync(source, target);
console.log(`copy-openapi: published API ${version} to static/api/openapi.yaml`);
