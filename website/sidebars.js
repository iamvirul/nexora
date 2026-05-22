// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docsSidebar: [
    {
      type: 'doc',
      id: 'intro',
      label: 'Introduction',
    },
    {
      type: 'doc',
      id: 'getting-started',
      label: 'Getting Started',
    },
    {
      type: 'category',
      label: 'Core Concepts',
      collapsed: false,
      items: [
        'concepts/pluggable-planner',
        'concepts/reactive-amendments',
        'concepts/capability-contracts',
      ],
    },
    {
      type: 'category',
      label: 'Modules',
      collapsed: false,
      items: [
        'modules/overview',
        'modules/nexora-api',
        'modules/nexora-core',
        'modules/nexora-plugin-spi',
        'modules/nexora-planner',
        'modules/nexora-executor',
        'modules/nexora-registry',
        'modules/nexora-retry',
        'modules/nexora-saga',
        'modules/nexora-event',
        'modules/nexora-tracing',
        'modules/nexora-persistence',
        'modules/nexora-plugin-loader',
        'modules/nexora-runtime',
      ],
    },
    {
      type: 'doc',
      id: 'writing-plugins',
      label: 'Writing Plugins',
    },
    {
      type: 'doc',
      id: 'observability',
      label: 'Observability',
    },
    {
      type: 'doc',
      id: 'cli',
      label: 'CLI Reference',
    },
  ],
};

export default sidebars;
