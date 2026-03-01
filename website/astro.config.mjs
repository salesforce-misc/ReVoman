import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

export default defineConfig({
  site: "https://salesforce-misc.github.io",
  base: "/ReVoman",
  integrations: [
    starlight({
      title: "ReVoman",
      description:
        "Multi-format API Orchestration & Automation tool for JVM (Java/Kotlin) from Salesforce",
      social: [
        {
          icon: "github",
          label: "GitHub",
          href: "https://github.com/salesforce-misc/ReVoman",
        },
      ],
      editLink: {
        baseUrl:
          "https://github.com/salesforce-misc/ReVoman/edit/master/website/",
      },
      customCss: ["./src/styles/custom.css"],
      expressiveCode: {
        themes: ["github-dark", "github-light"],
      },
      sidebar: [
        {
          label: "Introduction",
          items: [
            { label: "What is ReVoman?", link: "/" },
            { label: "Why ReVoman?", slug: "about/why-revoman" },
          ],
        },
        {
          label: "Getting Started",
          items: [
            { label: "Quick Start", slug: "getting-started" },
            { label: "Adoption Guide", slug: "getting-started/adoption-guide" },
            { label: "Rundown", slug: "getting-started/rundown" },
            {
              label: "Advanced Example",
              slug: "getting-started/advanced-example",
            },
          ],
        },
        {
          label: "Guides",
          items: [
            {
              label: "Template Formats",
              slug: "guides/template-formats",
            },
            { label: "Variables", slug: "guides/variables" },
            {
              label: "Config Management",
              slug: "guides/config-management",
            },
            {
              label: "Type Safety & Marshalling",
              slug: "guides/type-safety",
            },
            {
              label: "Execution Control",
              slug: "guides/execution-control",
            },
            { label: "Pre/Post Hooks", slug: "guides/hooks" },
            {
              label: "Scripts & Response Handlers",
              slug: "guides/scripts",
            },
            {
              label: "Mutable Environment",
              slug: "guides/mutable-environment",
            },
            {
              label: "Custom Dynamic Variables",
              slug: "guides/custom-dynamic-variables",
            },
            { label: "Polling (Async Steps)", slug: "guides/polling" },
            {
              label: "Mock Server Testing",
              slug: "guides/mock-server",
            },
          ],
        },
        {
          label: "Troubleshooting",
          items: [
            {
              label: "Debugging & Step Procedure",
              slug: "troubleshooting",
            },
            {
              label: "Failure Hierarchy",
              slug: "troubleshooting/failure-hierarchy",
            },
            { label: "Logging", slug: "troubleshooting/logging" },
          ],
        },
        {
          label: "Reference",
          items: [
            {
              label: "Rundown Serialization",
              slug: "reference/rundown-serialization",
            },
            { label: "FAQs", slug: "reference/faqs" },
          ],
        },
        {
          label: "About",
          items: [
            {
              label: "Unique Selling Points",
              slug: "about/usp",
            },
            { label: "Performance", slug: "about/performance" },
            { label: "Roadmap", slug: "about/roadmap" },
            { label: "Contributing", slug: "about/contributing" },
          ],
        },
      ],
    }),
  ],
});
