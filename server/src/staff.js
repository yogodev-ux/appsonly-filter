// The staff portal: a separate website for support staff (part 4 builds its pages).
import { htmlPage } from './web.js';

export function createStaffPortal() {
  async function handle(request, url, who) {
    return htmlPage({
      title: 'Apps Only support',
      css: 'body{font-family:system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 16px;background:#fff;color:#111}',
      body: '<h1>Apps Only support</h1><p>The staff portal is being built.</p><form method="post" action="/logout"><button>Sign out</button></form>',
    });
  }
  return { handle };
}
