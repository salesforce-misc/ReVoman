# ReVoman Documentation Website

Documentation site for ReVoman, built with [Astro Starlight](https://starlight.astro.build/) and integrated into the Gradle build system.

**Live site**: https://salesforce-misc.github.io/ReVoman/

## Gradle Commands

```bash
# Install dependencies (runs automatically as a dependency of other tasks)
./gradlew :website:npmInstall

# Start dev server with hot reload (http://localhost:4321/ReVoman)
./gradlew :website:websiteDev

# Build static site → website/dist/
./gradlew :website:websiteBuild

# Preview production build locally
./gradlew :website:websitePreview
```

## Project Structure

```
website/
├── build.gradle.kts           # Gradle subproject (Node plugin + tasks)
├── astro.config.mjs           # Starlight config (sidebar, theme, site URL)
├── package.json               # Node dependencies (Astro + Starlight)
├── src/
│   ├── content.config.ts      # Astro content layer config
│   ├── content/docs/          # MDX documentation pages
│   │   ├── index.mdx          # Landing page (splash template)
│   │   ├── getting-started/   # Quick Start, Rundown, Advanced Example
│   │   ├── guides/            # Feature guides (9 pages)
│   │   ├── troubleshooting/   # Debugging, failures, logging
│   │   ├── reference/         # Rundown serialization, FAQs
│   │   └── about/             # Why ReVoman, USP, roadmap, contributing
│   ├── assets/images/         # PNG images (optimized by Astro)
│   └── styles/custom.css      # Salesforce-blue accent theme
└── public/images/             # Static assets served as-is (GIFs)
```

## Adding or Editing Pages

1. Create or edit `.mdx` files in `src/content/docs/`
2. Each page needs YAML frontmatter with at least `title`:
   ```mdx
   ---
   title: My Page Title
   description: Optional description for SEO
   ---

   Content here...
   ```
3. If adding a new page, register it in the `sidebar` array in `astro.config.mjs`
4. Use Starlight's built-in components and directives:
   - Admonitions: `:::tip`, `:::note`, `:::caution`, `:::danger`
   - Components: `import { Tabs, TabItem, Card, CardGrid } from '@astrojs/starlight/components';`
   - Code blocks: ` ```java title="MyFile.java" ` with syntax highlighting

## Images

- **PNG images** → Place in `src/assets/images/` (Astro optimizes them to WebP)
  - Reference: `![alt](../../../assets/images/file.png)` (adjust `../` depth based on page location)
- **GIFs/videos** → Place in `public/images/` (served as-is, no optimization)
  - Reference: `![alt](/ReVoman/images/file.gif)`

## Deployment

Deployment to GitHub Pages is automated via `.github/workflows/deploy-website.yml`:
- **Triggers**: Push to `master` that changes `website/**`, or manual dispatch
- **Prerequisite**: Enable GitHub Pages with "GitHub Actions" source in repo Settings > Pages

## Tech Details

- **Astro 5.x** + **Starlight 0.34** — static site generation with built-in search (Pagefind), dark mode, responsive sidebar
- **Node 22** — required by Astro 5.x (Gradle downloads it automatically)
- **Expressive Code** — syntax highlighting with GitHub dark/light themes
- Content source: Converted from `README.adoc`, `CONTRIBUTING.adoc`, and `docs/rundown-serialization.md`
