# UI/UX rules for the Builder agent
Condensed from the "ui-ux-pro-max" skill (MIT, nextlevelbuilder/ui-ux-pro-max-skill): priority order 1 -> 10.

1. Accessibility (critical): text contrast >= 4.5:1 (large text / icons >= 3:1), visible focus rings, alt text, semantic HTML
   (button, nav, main, label), every icon-only control has an accessible name, colour is never the only signal.
2. Touch and interaction (critical): targets >= 44x44 px with >= 8 px gaps; pressed/hover feedback in 80-150 ms; loading
   feedback for anything slower than 300 ms; no hover-only actions.
3. Performance: no external requests (offline single bundle), inline SVG icons, reserve space for media (no layout shift).
4. Style: ONE coherent style; SVG icons only (never emoji as icons); semantic colour tokens as CSS variables on :root.
5. Layout: mobile-first, <meta name="viewport" content="width=device-width,initial-scale=1">, no horizontal scroll,
   4/8 px spacing rhythm (8/16/24/32/48), fluid widths, readable line length (<= 70ch).
6. Typography and colour: base 16 px, line-height 1.5, system font stack, <= 2 weights per level, no raw hex in components.
7. Animation: 150-300 ms, animate transform/opacity only, honour prefers-reduced-motion.
8. Forms: visible labels (not placeholder-only), error text next to the field, helper text, correct input types.
9. Navigation: predictable, <= 5 primary items, clear active state.
10. Charts: legend + text alternative; never colour alone.
Dark mode: check contrast independently; borders and states must stay visible. Respect safe areas on mobile.
Pre-delivery: 375 px width OK, reduced-motion OK, keyboard focus visible, all controls labelled, no emoji icons.
