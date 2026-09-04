import puppeteer from 'puppeteer';

const APP_URL = process.env.TASKPILOT_APP_URL || 'http://127.0.0.1:5173';
const TEST_EMAIL = process.env.TASKPILOT_TEST_EMAIL;
const TEST_PASSWORD = process.env.TASKPILOT_TEST_PASSWORD;

async function submitSpaLogin(page) {
  const loginResponse = await Promise.all([
    page.waitForResponse(
      response => response.url().includes('/api/v1/auth/login'),
      { timeout: 30000 }
    ).catch(() => null),
    page.click('button[type="submit"]')
  ]).then(([response]) => response);

  if (loginResponse && loginResponse.status() >= 400) {
    throw new Error(`Login request failed with HTTP ${loginResponse.status()}`);
  }

  await page.waitForFunction(
    () => window.location.pathname !== '/login' || Boolean(localStorage.getItem('taskpilot_access_token')),
    { timeout: 30000 }
  );

  const hasTokenOnLoginPage = await page.evaluate(
    () => window.location.pathname === '/login' && Boolean(localStorage.getItem('taskpilot_access_token'))
  );
  if (hasTokenOnLoginPage) {
    await page.goto(`${APP_URL}/`, { waitUntil: 'networkidle2' });
  }

  await page.waitForSelector('h1, main', { timeout: 30000 });
}

(async () => {
  const targetRoute = process.argv[2] || '/';
  const requiresAuth = process.argv[3] !== 'false';

  const browser = await puppeteer.launch({
    headless: "new",
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  const page = await browser.newPage();
  
  // Set to mobile viewport (375x667)
  await page.setViewport({
    width: 375,
    height: 667,
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true
  });

  const report = {
    route: targetRoute,
    viewport: '375x667',
    issues: []
  };

  try {
    if (requiresAuth) {
      if (!TEST_EMAIL || !TEST_PASSWORD) {
        throw new Error('Set TASKPILOT_TEST_EMAIL and TASKPILOT_TEST_PASSWORD before running this script.');
      }
      await page.goto(`${APP_URL}/login`, { waitUntil: 'networkidle2' });
      await page.type('input[type="email"]', TEST_EMAIL);
      await page.type('input[type="password"]', TEST_PASSWORD);
      await submitSpaLogin(page);
    }

    await page.goto(`${APP_URL}${targetRoute}`, { waitUntil: 'networkidle2' });
    await new Promise(resolve => setTimeout(resolve, 2000)); // Wait for render

    // Run responsive checks inside the page context
    const checkResults = await page.evaluate(() => {
      const issues = [];

      // 1. Check for horizontal overflow
      const docWidth = document.documentElement.scrollWidth;
      const viewWidth = document.documentElement.clientWidth;
      if (docWidth > viewWidth) {
        issues.push({
          type: 'horizontal_overflow',
          selector: 'document',
          details: `Document scrollWidth (${docWidth}px) exceeds clientWidth (${viewWidth}px) by ${docWidth - viewWidth}px`
        });

        // Find elements that overflow
        const elements = document.querySelectorAll('*');
        for (const el of elements) {
          const rect = el.getBoundingClientRect();
          if (rect.right > viewWidth) {
            // Find unique selector
            let selector = el.tagName.toLowerCase();
            if (el.id) selector += `#${el.id}`;
            if (el.className) selector += `.${Array.from(el.classList).join('.')}`;
            
            issues.push({
              type: 'overflowing_element',
              selector: selector.substring(0, 100),
              details: `Element bounds: left=${rect.left}px, right=${rect.right}px (viewport limit=${viewWidth}px)`
            });
          }
        }
      }

      // 2. Check for small touch targets (buttons and links)
      const touchTargets = document.querySelectorAll('button, a, [role="button"]');
      for (const el of touchTargets) {
        const rect = el.getBoundingClientRect();
        // Only check visible elements
        if (rect.width > 0 && rect.height > 0) {
          const isTooSmall = rect.height < 40 || rect.width < 40;
          if (isTooSmall) {
            let selector = el.tagName.toLowerCase();
            if (el.id) selector += `#${el.id}`;
            const text = (el.innerText || el.textContent || '').trim().substring(0, 30);
            
            issues.push({
              type: 'small_touch_target',
              selector: selector,
              details: `Target size is ${Math.round(rect.width)}x${Math.round(rect.height)}px. Should be at least 40x40px. Text: "${text}"`
            });
          }
        }
      }

      // 3. Check for tiny text size
      const textElements = document.querySelectorAll('p, span, a, label, h1, h2, h3, h4, h5, h6, td, th');
      for (const el of textElements) {
        // Skip icons
        if (el.querySelector('svg') || el.classList.contains('lucide') || el.className.includes('icon')) {
          continue;
        }
        const style = window.getComputedStyle(el);
        const fontSizeStr = style.fontSize;
        if (fontSizeStr && fontSizeStr.endsWith('px')) {
          const fontSize = parseFloat(fontSizeStr);
          if (fontSize > 0 && fontSize < 11) {
            let selector = el.tagName.toLowerCase();
            if (el.id) selector += `#${el.id}`;
            const text = (el.innerText || el.textContent || '').trim().substring(0, 30);
            if (text) {
              issues.push({
                type: 'tiny_text',
                selector: selector,
                details: `Font size is ${fontSizeStr}. Text: "${text}"`
              });
            }
          }
        }
      }

      return issues;
    });

    report.issues = checkResults;

  } catch (error) {
    report.issues.push({
      type: 'error',
      selector: 'system',
      details: error.message
    });
  } finally {
    await browser.close();
    console.log(JSON.stringify(report, null, 2));
  }
})();
