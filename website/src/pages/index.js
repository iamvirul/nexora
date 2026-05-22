import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Layout from '@theme/Layout';
import CodeBlock from '@theme/CodeBlock';
import styles from './index.module.css';

function IconPlanner() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="6" cy="6" r="3"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="18" r="3"/>
      <line x1="9" y1="6" x2="15" y2="6"/><line x1="6" y1="9" x2="6" y2="15"/><line x1="18" y1="9" x2="18" y2="15"/><line x1="9" y1="18" x2="15" y2="18"/>
    </svg>
  );
}

function IconReactive() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
      <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/>
    </svg>
  );
}

function IconContract() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
      <polyline points="9 12 11 14 15 10"/>
    </svg>
  );
}

function IconSaga() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2" y="9" width="5" height="6" rx="1"/><rect x="9.5" y="9" width="5" height="6" rx="1"/><rect x="17" y="9" width="5" height="6" rx="1"/>
      <line x1="7" y1="12" x2="9.5" y2="12"/><line x1="14.5" y1="12" x2="17" y2="12"/>
    </svg>
  );
}

function IconParallel() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="3" y1="8" x2="21" y2="8"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="16" x2="21" y2="16"/>
      <circle cx="3" cy="8" r="1" fill="currentColor" stroke="none"/>
      <circle cx="3" cy="12" r="1" fill="currentColor" stroke="none"/>
      <circle cx="3" cy="16" r="1" fill="currentColor" stroke="none"/>
    </svg>
  );
}

function IconTracing() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
    </svg>
  );
}

const FEATURES = [
  {
    Icon: IconPlanner,
    title: 'Pluggable Planner',
    description: 'Swap in an LLM, constraint solver, or your own rule engine. The planner is a plugin; the built-in keyword matcher is just the default.',
  },
  {
    Icon: IconReactive,
    title: 'Reactive Amendment',
    description: 'Reshape the remaining plan at runtime. Inject steps, skip pending ones, or override downstream inputs, all without touching the planner.',
  },
  {
    Icon: IconContract,
    title: 'Capability Contracts',
    description: 'Capabilities declare expected latency and error rates. Nexora silently reroutes to a fallback when the primary breaches its contract.',
  },
  {
    Icon: IconSaga,
    title: 'Saga Orchestration',
    description: 'Model long-running distributed transactions as sagas. Automatic compensation steps run on failure, leaving no partial state behind.',
  },
  {
    Icon: IconParallel,
    title: 'Parallel Execution',
    description: 'Independent steps are scheduled and run concurrently. The engine detects data-flow dependencies and sequences only what must be sequential.',
  },
  {
    Icon: IconTracing,
    title: 'Built-in Observability',
    description: 'Every plan execution emits structured spans with full trace context. Drop-in OpenTelemetry integration with no manual instrumentation required.',
  },
];

const QUICKSTART_CODE = `NexoraEngine engine = NexoraEngine.builder()
    .capability(new FetchUserCapability())
    .capability(new SendEmailCapability())
    .capability(new AuditLogCapability())
    .build();

ExecutionResult result = engine.execute(
    Intent.of("notify-user"),
    Map.of("userId", "u-123")
);`;

function FeatureCard({ Icon, title, description }) {
  return (
    <div className={styles.featureCard}>
      <div className={styles.featureIconWrap}>
        <Icon />
      </div>
      <h3 className={styles.featureTitle}>{title}</h3>
      <p className={styles.featureDesc}>{description}</p>
    </div>
  );
}

function Hero() {
  const { siteConfig } = useDocusaurusContext();
  const logoSrc = useBaseUrl('/img/logo.png');
  return (
    <header className={styles.hero}>
      <div className={styles.heroBgDot} aria-hidden="true" />
      <div className={styles.heroBgGlow} aria-hidden="true" />
      <div className={styles.heroContent}>
        <div className={styles.heroLogoWrap}>
          <img src={logoSrc} alt="Nexora" className={styles.heroLogo} />
        </div>
        <h1 className={styles.heroTitle}>{siteConfig.title}</h1>
        <p className={styles.heroSubtitle}>{siteConfig.tagline}</p>
        <div className={styles.heroCta}>
          <Link className={clsx('button button--primary button--lg', styles.ctaPrimary)} to="/docs/intro">
            Get Started
          </Link>
          <Link
            className={clsx('button button--outline button--lg', styles.ctaSecondary)}
            href="https://github.com/iamvirul/nexora"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" style={{marginRight: '0.5rem', verticalAlign: 'text-bottom'}} aria-hidden="true">
              <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/>
            </svg>
            GitHub
          </Link>
        </div>
        <div className={styles.heroBadge}>Java 21 &nbsp;·&nbsp; Maven 3.9+</div>
      </div>
    </header>
  );
}

export default function Home() {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout title={siteConfig.title} description="Nexora is a reactive, intent-based Java execution engine.">
      <Hero />
      <main>
        <section className={styles.featuresSection}>
          <div className="container">
            <h2 className={styles.sectionHeading}>Built for production intent execution</h2>
            <p className={styles.sectionSubheading}>
              Declare capabilities, submit a goal, and let Nexora handle the planning, scheduling, retrying, and tracing. Production-grade guarantees built in.
            </p>
            <div className={styles.featuresGrid}>
              {FEATURES.map((f, i) => (
                <FeatureCard key={i} {...f} />
              ))}
            </div>
          </div>
        </section>

        <section className={styles.quickstartSection}>
          <div className="container">
            <div className={styles.quickstartInner}>
              <div className={styles.quickstartText}>
                <div className={styles.quickstartTag}>Quick Start</div>
                <h2>Zero boilerplate. Just declare your intent.</h2>
                <p>
                  Register capabilities, submit a goal, and collect results. Nexora plans the steps, runs them in parallel where possible, and retries automatically on failure.
                </p>
                <Link className={clsx('button button--primary', styles.ctaPrimary)} to="/docs/getting-started">
                  Read the Guide
                </Link>
                <Link className={clsx('button button--outline button--secondary', styles.ctaSecondaryAlt)} to="/docs/intro">
                  Introduction
                </Link>
              </div>
              <div className={styles.codeBlockWrap}>
                <CodeBlock language="java" title="NexoraEngine.java">
                  {QUICKSTART_CODE}
                </CodeBlock>
              </div>
            </div>
          </div>
        </section>
      </main>
    </Layout>
  );
}
