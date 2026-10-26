// Checks the app's season data against the official sites it is NOT built
// from. The nightly scrape reads box scores from the NCAA's live-stats feed
// and works the Big 12 table out from results; this reads Kansas Athletics'
// own stats pages, the Big 12's standings and ncaa.com's rankings, so a
// mistake anywhere in the pipeline shows up as a difference.
//
// Runs in GitHub Actions (data-cross-check.yml), which can reach those sites.
//
//   node scripts/data-cross-check.mjs probe
//       Prints what each official page holds - its text, tables and links -
//       so the readers in "check" can be written against the real pages.

import { chromium } from 'playwright';

const PAGES = [
  ['KU season stats', 'https://kuathletics.com/sports/womens-volleyball/stats/2026'],
  ['KU stats (no season)', 'https://kuathletics.com/sports/womens-volleyball/stats'],
  ['KU schedule', 'https://kuathletics.com/sports/womens-volleyball/schedule'],
  ['Big 12 standings', 'https://big12sports.com/standings.aspx?path=wvball'],
  ['AVCA poll (ncaa.com)', 'https://www.ncaa.com/rankings/volleyball-women/d1/avca-rankings'],
  ['RPI (ncaa.com)', 'https://www.ncaa.com/rankings/volleyball-women/d1/ncaa-womens-volleyball-rpi'],
  ['NCAA stats index', 'https://www.ncaa.com/stats/volleyball-women/d1'],
  ['NCAA official team stats', 'https://stats.ncaa.org/teams/history?org_id=328&sport_code=WVB'],
];

async function open(context, url) {
  const page = await context.newPage();
  const resp = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(8_000);
  for (let i = 0; i < 10; i++) {
    await page.evaluate(() => window.scrollBy(0, 1200));
    await page.waitForTimeout(300);
  }
  return { page, status: resp?.status() ?? 0 };
}

/** The page's tables as rows of cell text, and its links, for the log. */
async function describe(page) {
  return page.evaluate(() => {
    const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
    const tables = [...document.querySelectorAll('table')].map((t) => ({
      caption: clean(t.caption?.innerText || t.getAttribute('aria-label') || ''),
      rows: [...t.querySelectorAll('tr')].map((tr) => [...tr.querySelectorAll('th,td')].map((c) => clean(c.innerText))),
    }));
    const links = [...document.querySelectorAll('a[href]')]
      .map((a) => [clean(a.innerText), a.href])
      .filter(([text, href]) => /box|stat|pdf|recap|game/i.test(text + ' ' + href));
    return { title: document.title, text: document.body ? document.body.innerText : '', tables, links };
  });
}

async function probe() {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36',
  });
  for (const [name, url] of PAGES) {
    console.log(`\n######## ${name}: ${url}`);
    try {
      const { page, status } = await open(context, url);
      const d = await describe(page);
      console.log(`status ${status} · final ${page.url()} · title ${d.title}`);
      console.log(`---- text (first 4000 of ${d.text.length} chars)\n${d.text.slice(0, 4000)}`);
      d.tables.forEach((t, i) => {
        console.log(`---- table ${i + 1}/${d.tables.length} "${t.caption}" ${t.rows.length} rows`);
        for (const r of t.rows.slice(0, 6)) console.log('  | ' + r.join(' | '));
        if (t.rows.length > 6) console.log(`  … last: | ${t.rows[t.rows.length - 1].join(' | ')}`);
      });
      console.log(`---- ${d.links.length} links about box scores / stats`);
      for (const [text, href] of d.links.slice(0, 60)) console.log(`  ${text} -> ${href}`);
      await page.close();
    } catch (e) {
      console.log(`FAILED: ${e.message}`);
    }
  }
  await browser.close();
}

const mode = process.argv[2];
if (mode === 'probe') await probe();
else {
  console.error('usage: data-cross-check.mjs probe');
  process.exit(2);
}
