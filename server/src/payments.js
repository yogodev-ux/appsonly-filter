// Payment providers. "test" marks a device paid with one button (local and CI only).
// "stripe" uses Stripe Checkout; the webhook marks the device paid.
const enc = new TextEncoder();

export class TestPayments {
  name = 'test';
  async checkoutUrl(device, baseUrl) {
    return `${baseUrl}/pay/${device.payCode}/test`;
  }
  async parseWebhook() {
    return null;
  }
}

export class StripePayments {
  name = 'stripe';
  constructor({ secretKey, webhookSecret, priceCents, currency, productName }) {
    Object.assign(this, { secretKey, webhookSecret, priceCents, currency, productName });
  }

  async checkoutUrl(device, baseUrl) {
    const form = new URLSearchParams({
      mode: 'payment',
      client_reference_id: device.id,
      success_url: `${baseUrl}/pay/${device.payCode}?done=1`,
      cancel_url: `${baseUrl}/pay/${device.payCode}`,
      'line_items[0][quantity]': '1',
      'line_items[0][price_data][currency]': this.currency,
      'line_items[0][price_data][unit_amount]': String(this.priceCents),
      'line_items[0][price_data][product_data][name]': this.productName,
      'metadata[pay_code]': device.payCode,
    });
    const r = await fetch('https://api.stripe.com/v1/checkout/sessions', {
      method: 'POST',
      headers: { authorization: `Bearer ${this.secretKey}`, 'content-type': 'application/x-www-form-urlencoded' },
      body: form,
    });
    if (!r.ok) throw new Error(`stripe checkout failed: ${r.status}`);
    return (await r.json()).url;
  }

  /** Verifies the Stripe-Signature header; returns the paid device id, or null. */
  async parseWebhook(request) {
    const raw = await request.text();
    const header = request.headers.get('stripe-signature') ?? '';
    const parts = Object.fromEntries(header.split(',').map((p) => p.split('=')));
    if (!parts.t || !parts.v1) return null;
    if (Math.abs(Date.now() / 1000 - Number(parts.t)) > 300) return null;
    const key = await crypto.subtle.importKey('raw', enc.encode(this.webhookSecret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(`${parts.t}.${raw}`)));
    const hex = [...mac].map((b) => b.toString(16).padStart(2, '0')).join('');
    if (hex !== parts.v1) return null;
    const event = JSON.parse(raw);
    if (event.type !== 'checkout.session.completed') return null;
    const s = event.data.object;
    return s.payment_status === 'paid' ? s.client_reference_id : null;
  }
}
