import { defineConfig } from 'vitepress'

const base = process.env.VITEPRESS_BASE || '/'

export default defineConfig({
  lang: 'en-US',
  title: 'Minedit',
  description: 'Build and edit Minecraft structures with AI agents.',
  base,
  cleanUrls: true,
  lastUpdated: true,
  head: [
    ['link', { rel: 'icon', href: `${base}minedit-mark.svg`, type: 'image/svg+xml' }],
    ['meta', { name: 'theme-color', content: '#111827' }]
  ],
  themeConfig: {
    logo: '/minedit-mark.svg',
    siteTitle: 'Minedit',
    search: {
      provider: 'local'
    },
    nav: [
      { text: 'Guide', link: '/getting-started' },
      { text: 'Providers', link: '/providers' },
      { text: 'Commands', link: '/commands' },
      { text: 'Troubleshooting', link: '/troubleshooting' }
    ],
    sidebar: [
      {
        text: 'Start',
        items: [
          { text: 'Getting Started', link: '/getting-started' },
          { text: 'Safety and Costs', link: '/safety-and-costs' },
          { text: 'Commands', link: '/commands' }
        ]
      },
      {
        text: 'Build and Edit',
        items: [
          { text: 'Build Modes', link: '/build-modes' },
          { text: 'Editing', link: '/editing' },
          { text: 'Export and Import', link: '/export-import' },
          { text: 'Examples', link: '/examples/' }
        ]
      },
      {
        text: 'Providers',
        items: [
          { text: 'Overview', link: '/providers' },
          { text: 'Codex', link: '/codex' },
          { text: 'Cursor', link: '/cursor' },
          { text: 'Hermes', link: '/hermes' }
        ]
      }
    ],
    socialLinks: [
      { icon: 'github', link: 'https://github.com/Codename-11/minedit' }
    ],
    footer: {
      message: 'Experimental software. Back up worlds before testing large builds.',
      copyright: 'Minedit'
    }
  }
})
