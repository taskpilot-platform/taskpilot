---
name: mobile-responsive-test
description: Test mobile responsiveness of TaskPilot frontend pages using Puppeteer screenshots at multiple viewport sizes. Use when verifying mobile layout changes, checking for overflow issues, or comparing desktop vs mobile views.
---

# Mobile Responsive Testing Skill

Test TaskPilot frontend pages across multiple mobile viewport sizes using Puppeteer. This skill captures screenshots and checks for common responsive design issues.

## Prerequisites

- The TaskPilot frontend dev server must be running (default: `http://127.0.0.1:5173`)
- Puppeteer is already in the project's `devDependencies`
- Node.js 18+ with ES module support

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `TASKPILOT_TEST_EMAIL` | **Yes** | — | Login email for authenticated routes |
| `TASKPILOT_TEST_PASSWORD` | **Yes** | — | Login password for authenticated routes |
| `TASKPILOT_APP_URL` | No | `http://127.0.0.1:5173` | Base URL of the running frontend |

## Scripts

All scripts must be run from the **taskpilot-frontend** project root (`/home/fhu_thjen/projects/se121/taskpilot-frontend`).

### 1. `mobile-screenshot.js` — Capture Screenshots at Multiple Viewports

Captures full-page screenshots of a given route across 5 device viewports.

```bash
# Capture all viewports for a route
node .agents/skills/mobile-responsive-test/scripts/mobile-screenshot.js /projects

# Capture specific viewports only (comma-separated)
node .agents/skills/mobile-responsive-test/scripts/mobile-screenshot.js /projects --viewports iPhone_SE,Desktop

# Capture the login page (no auth required)
node .agents/skills/mobile-responsive-test/scripts/mobile-screenshot.js /login
```

Screenshots are saved to:
```
scripts/ui-automation/temp/mobile/{route_sanitized}_{viewport_name}.png
```

### 2. `responsive-check.js` — Automated Responsive Issue Detection

Checks a route at mobile viewport (375×667) for common responsive issues and outputs a JSON report.

```bash
node .agents/skills/mobile-responsive-test/scripts/responsive-check.js /projects
```

Output is a JSON report to stdout:
```json
{
  "route": "/projects",
  "viewport": { "width": 375, "height": 667 },
  "issues": [
    { "type": "horizontal-overflow", "selector": "body", "details": "scrollWidth 412 > clientWidth 375" },
    { "type": "small-touch-target", "selector": "button.icon-btn", "details": "height 32px < 44px minimum" }
  ]
}
```

## Viewports Tested

| Device | Width | Height | Scale Factor |
|---|---|---|---|
| iPhone SE | 375 | 667 | 2x |
| iPhone 14 Pro | 393 | 852 | 3x |
| Galaxy S21 | 360 | 800 | 3x |
| iPad Mini | 768 | 1024 | 2x |
| Desktop | 1280 | 800 | 1x |

## Routes to Test

The following 12 routes cover the TaskPilot frontend. Routes marked with 🔒 require authentication.

| # | Route | Priority | Auth |
|---|---|---|---|
| 1 | `/login` | Medium | — |
| 2 | `/register` | Medium | — |
| 3 | `/` | High | 🔒 |
| 4 | `/projects` | High | 🔒 |
| 5 | `/notifications` | Medium | 🔒 |
| 6 | `/comments` | Medium | 🔒 |
| 7 | `/copilot` | High | 🔒 |
| 8 | `/profile` | Low | 🔒 |
| 9 | `/my-skills` | Low | 🔒 |
| 10 | `/admin/users` | Low | 🔒 |
| 11 | `/admin/skills` | Low | 🔒 |
| 12 | `/admin/settings` | Low | 🔒 |

## Interpreting Results

### Screenshots (`mobile-screenshot.js`)

- Compare screenshots across viewports to verify layout adapts correctly
- Look for: text overflow, horizontal scrollbars, overlapping elements, cut-off content
- Use the `pdf-reviewer` skill or view the PNG files directly to spot visual anomalies
- Desktop screenshot serves as the baseline for comparison

### Responsive Check Report (`responsive-check.js`)

The JSON report contains categorized issues:

| Issue Type | What It Means |
|---|---|
| `horizontal-overflow` | Page content extends beyond the viewport width — causes horizontal scrolling |
| `overflow-element` | A specific element extends beyond the viewport boundary |
| `small-touch-target` | Button or link is smaller than the 44px minimum recommended touch target |
| `small-text` | Text size is below 12px — hard to read on mobile |
| `fixed-overlap` | Fixed-position elements that may overlap content or each other |

### Recommended Workflow

1. Start with **high-priority** routes (`/`, `/projects`, `/copilot`)
2. Run `responsive-check.js` first to identify programmatic issues
3. Run `mobile-screenshot.js` on routes with issues to visually confirm
4. Fix issues using Tailwind responsive prefixes (`sm:`, `md:`, `lg:`, `xl:`)
5. Re-run checks to verify fixes

## Resources

- [mobile-viewports.json](resources/mobile-viewports.json) — Viewport and route definitions (machine-readable)
- [responsive-checklist.md](references/responsive-checklist.md) — Manual testing checklist and Tailwind reference
