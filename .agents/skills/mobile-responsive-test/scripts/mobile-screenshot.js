import puppeteer from 'puppeteer';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const APP_URL = process.env.TASKPILOT_APP_URL || 'http://127.0.0.1:5173';
const TEST_EMAIL = process.env.TASKPILOT_TEST_EMAIL;
const TEST_PASSWORD = process.env.TASKPILOT_TEST_PASSWORD;

const VIEWPORTS = [
  { name: 'iPhone_SE', width: 375, height: 667, deviceScaleFactor: 2, isMobile: true },
  { name: 'iPhone_14_Pro', width: 393, height: 852, deviceScaleFactor: 3, isMobile: true },
  { name: 'Galaxy_S21', width: 360, height: 800, deviceScaleFactor: 3, isMobile: true },
  { name: 'iPad_Mini', width: 768, height: 1024, deviceScaleFactor: 2, isMobile: true },
  { name: 'Desktop', width: 1280, height: 800, deviceScaleFactor: 1, isMobile: false }
];

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
  const requiresAuth = process.argv[3] !== 'false'; // default true

  console.log(`Starting mobile responsive capture for route: ${targetRoute}`);

  const browser = await puppeteer.launch({
    headless: "new",
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  const page = await browser.newPage();

  try {
    if (requiresAuth) {
      if (!TEST_EMAIL || !TEST_PASSWORD) {
        throw new Error('Set TASKPILOT_TEST_EMAIL and TASKPILOT_TEST_PASSWORD before running this script.');
      }
      console.log('Logging in...');
      await page.goto(`${APP_URL}/login`, { waitUntil: 'networkidle2' });
      await page.type('input[type="email"]', TEST_EMAIL);
      await page.type('input[type="password"]', TEST_PASSWORD);
      await submitSpaLogin(page);
    }

    const outputDir = path.join(__dirname, '..', '..', '..', 'taskpilot-frontend', 'scripts', 'ui-automation', 'temp', 'mobile');
    if (!fs.existsSync(outputDir)) {
      fs.mkdirSync(outputDir, { recursive: true });
    }

    const routeName = targetRoute.replace(/\//g, '_').replace(/^_/, '') || 'root';

    for (const vp of VIEWPORTS) {
      console.log(`Capturing viewport ${vp.name} (${vp.width}x${vp.height})...`);
      await page.setViewport({
        width: vp.width,
        height: vp.height,
        deviceScaleFactor: vp.deviceScaleFactor,
        isMobile: vp.isMobile,
        hasTouch: vp.isMobile
      });

      await page.goto(`${APP_URL}${targetRoute}`, { waitUntil: 'networkidle2' });
      await new Promise(resolve => setTimeout(resolve, 2000)); // Wait for render/animations

      const outputPath = path.join(outputDir, `${routeName}_${vp.name}.png`);
      await page.screenshot({ path: outputPath, fullPage: true });
      console.log(`Saved screenshot: ${outputPath}`);
    }

  } catch (error) {
    console.error('Capture failed:', error);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
