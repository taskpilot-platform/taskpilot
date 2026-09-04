# Mobile Responsive Testing Checklist

## Layout & Structure

- [ ] No horizontal scrollbar at any viewport width
- [ ] Content does not overflow the viewport
- [ ] Sidebar collapses or becomes a hamburger menu on mobile
- [ ] Navigation is accessible and usable on small screens
- [ ] Modals and dialogs fit within the viewport
- [ ] Tables are horizontally scrollable or adapted for mobile
- [ ] Forms are single-column on mobile
- [ ] Cards stack vertically on narrow viewports

## Typography

- [ ] Base font size is at least **16px** on mobile (prevents iOS zoom on input focus)
- [ ] No text smaller than **12px** anywhere on the page
- [ ] Line length is comfortable (45–75 characters per line)
- [ ] Headings scale down appropriately on smaller screens
- [ ] Text is readable without zooming

## Touch Targets

- [ ] All interactive elements (buttons, links, inputs) have a minimum touch target of **44×44px**
  - Apple Human Interface Guidelines: 44pt minimum
  - Material Design: 48dp minimum
- [ ] Adequate spacing between adjacent touch targets (at least 8px gap)
- [ ] Dropdown menus and select elements are large enough to tap
- [ ] Close buttons on modals/toasts are easily tappable

## Images & Media

- [ ] Images are responsive (`max-width: 100%; height: auto;`)
- [ ] No images overflow their containers
- [ ] Icons are appropriately sized for mobile (minimum 24px)
- [ ] Avatar images scale correctly

## Forms & Inputs

- [ ] Input fields are full-width on mobile
- [ ] Input font size is at least 16px (prevents iOS auto-zoom)
- [ ] Labels are visible and associated with inputs
- [ ] Error messages are visible and do not cause layout shifts
- [ ] Submit buttons are easily reachable (consider sticky positioning)

## Common Issues to Look For

### Horizontal Overflow
- Fixed-width elements that don't adapt to viewport
- `min-width` values that exceed mobile viewport
- Flexbox items that don't wrap (`flex-wrap: wrap` missing)
- Absolute positioned elements extending beyond viewport
- Wide tables without `overflow-x: auto` wrapper
- Pre-formatted code blocks without word wrapping

### Content Overlap
- Fixed headers/footers overlapping scrollable content
- Z-index conflicts between overlapping elements
- Absolute positioned elements covering interactive content
- Toast notifications overlapping navigation

### Spacing Issues
- Padding/margin values too large for mobile
- Grid gaps that don't scale down
- Container padding that eats into content width

---

## Tailwind CSS Responsive Prefix Reference

Tailwind uses a **mobile-first** approach — unprefixed utilities apply to all screen sizes, and prefixed utilities apply at the specified breakpoint **and above**.

| Prefix | Min-Width | Typical Devices |
|---|---|---|
| *(none)* | 0px | All devices (mobile-first base) |
| `sm:` | **640px** | Large phones in landscape |
| `md:` | **768px** | Tablets (iPad Mini, etc.) |
| `lg:` | **1024px** | Small laptops, tablets in landscape |
| `xl:` | **1280px** | Desktops |
| `2xl:` | **1536px** | Large desktops |

### Common Responsive Patterns

```html
<!-- Stack on mobile, side-by-side on desktop -->
<div class="flex flex-col md:flex-row">

<!-- Full width on mobile, half on desktop -->
<div class="w-full md:w-1/2">

<!-- Hide on mobile, show on desktop -->
<div class="hidden md:block">

<!-- Show on mobile, hide on desktop -->
<div class="block md:hidden">

<!-- Responsive text sizes -->
<h1 class="text-2xl md:text-4xl lg:text-5xl">

<!-- Responsive padding -->
<div class="p-4 md:p-6 lg:p-8">

<!-- Responsive grid -->
<div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3">
```

---

## Touch Target Guidelines

### Minimum Sizes

| Platform | Minimum Size | Recommended |
|---|---|---|
| Apple (iOS) | 44×44 pt | 44×44 pt |
| Google (Material) | 48×48 dp | 48×48 dp |
| WCAG 2.5.8 | 24×24 CSS px | 44×44 CSS px |

### Implementation Tips

```css
/* Ensure minimum touch target with padding */
.touch-target {
  min-height: 44px;
  min-width: 44px;
  padding: 12px;
}

/* Extend touch area without changing visual size */
.small-icon-button {
  position: relative;
}
.small-icon-button::after {
  content: '';
  position: absolute;
  inset: -8px; /* extend tap area by 8px on all sides */
}
```

---

## Safe Area Insets

For devices with notches, rounded corners, or home indicators (iPhone X+):

```css
/* Apply safe area padding */
.safe-container {
  padding-left: env(safe-area-inset-left);
  padding-right: env(safe-area-inset-right);
  padding-bottom: env(safe-area-inset-bottom);
}

/* For fixed bottom bars */
.bottom-bar {
  padding-bottom: calc(16px + env(safe-area-inset-bottom));
}
```

Add the viewport meta tag to support safe areas:
```html
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
```

---

## Typography Minimum Sizes

| Element | Minimum Size | Recommended |
|---|---|---|
| Body text | 14px | 16px |
| Secondary text | 12px | 14px |
| Captions/labels | 11px | 12px |
| Input text | **16px** (required on iOS) | 16px |
| Buttons | 14px | 16px |

> **Important**: On iOS Safari, input fields with `font-size` less than **16px** trigger an automatic zoom when focused. Always use `font-size: 16px` or larger for form inputs.
