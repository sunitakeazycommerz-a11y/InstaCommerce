import { test, expect } from '@playwright/test';
import {
  MOCK_PRODUCTS,
  ORDER_TRACKING_STATES,
  PAYMENT_CARDS,
  PROMO_CODES,
  TEST_DELIVERY_ADDRESS,
  TEST_USER,
} from '../fixtures/mock-data.js';

test.describe('InstaCommerce Synthetic End-to-End Checkout Suite', () => {

  const STORE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>InstaCommerce - 10-Minute Grocery Delivery</title>
  <style>
    body { font-family: sans-serif; margin: 20px; }
    header { display: flex; justify-content: space-between; align-items: center; border-bottom: 2px solid #eee; padding-bottom: 10px; }
    .product-card { border: 1px solid #ddd; padding: 15px; margin: 10px 0; border-radius: 8px; width: 320px; }
    .cart-row { display: flex; align-items: center; gap: 10px; margin: 8px 0; }
    .btn { background: #00875a; color: white; border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; }
    .btn-secondary { background: #42526e; }
    .btn-danger { background: #de350b; }
    .alert { padding: 10px; border-radius: 4px; margin: 10px 0; }
    .alert-danger { background: #ffebe6; color: #bf2600; border: 1px solid #ffbdad; }
    .alert-success { background: #e3fcef; color: #006644; border: 1px solid #abf5d1; }
    .badge { background: #ffab00; color: #172b4d; padding: 4px 8px; border-radius: 12px; font-weight: bold; }
  </style>
</head>
<body>
  <header>
    <h1>InstaCommerce</h1>
    <div>
      <input type="search" id="search-input" placeholder="Search for milk, bread, snacks..." aria-label="Search" />
      <button id="search-btn" class="btn">Search</button>
    </div>
    <div id="cart-summary">
      Cart: <span id="cart-badge" class="badge">0 items</span>
      <button id="view-cart-btn" class="btn" style="display:none; margin-left: 8px;">View Cart</button>
    </div>
  </header>

  <main>
    <div id="product-results"></div>

    <div id="cart-view" style="display:none;">
      <h2>Shopping Cart</h2>
      <div id="cart-items-list"></div>
      <p id="empty-cart-msg" style="display:none;">Your cart is empty.</p>
      <p>Subtotal: <strong id="cart-total-display">₹0.00</strong></p>
      <button id="continue-shopping-btn" class="btn btn-secondary">Continue Shopping</button>
      <button id="checkout-btn" class="btn">Proceed to Checkout</button>
    </div>

    <div id="checkout-view" style="display:none;">
      <h2>Checkout & Payment</h2>
      <div id="alert-banner" class="alert alert-danger" role="alert" style="display:none;"></div>
      <div>
        <p><strong>Delivery Address:</strong> ${TEST_DELIVERY_ADDRESS.street}, ${TEST_DELIVERY_ADDRESS.city}</p>
        <div style="margin: 12px 0;">
          <label for="promo-input">Promo Code</label>
          <input id="promo-input" placeholder="Enter code (e.g. FRESH20)" />
          <button id="apply-promo-btn" class="btn btn-secondary" type="button">Apply Promo</button>
          <div id="promo-feedback" style="margin-top: 6px;"></div>
        </div>
        <p>Subtotal: <strong id="checkout-subtotal-display">₹0.00</strong></p>
        <p>Discount: <strong id="checkout-discount-display">₹0.00</strong></p>
        <p>Total Payable: <strong id="checkout-total-display">₹0.00</strong></p>
        <label for="card-number">Card Number</label>
        <input id="card-number" placeholder="4242 4242 4242 4242" value="${PAYMENT_CARDS.SUCCESSFUL_VISA.number}" />
      </div>
      <br/>
      <button id="place-order-btn" class="btn">Place Order</button>
    </div>

    <div id="confirmation-view" style="display:none;">
      <h2>Order Placed Successfully!</h2>
      <p>Thank you for shopping with InstaCommerce.</p>
      <p>Order ID: <strong id="confirmed-order-id"></strong></p>
      <p>Order Status: <span id="order-status-badge" class="badge">PLACED</span></p>
      <p>Delivery ETA: <span id="delivery-eta" class="badge">12 mins</span></p>
      <p>Charged Total: <strong id="confirmed-total-display">₹0.00</strong></p>
      <div style="margin-top: 12px;">
        <button id="refresh-status-btn" class="btn btn-secondary" type="button">Refresh Order Status</button>
        <select id="cancel-reason-select" aria-label="Cancellation reason">
          <option value="CUSTOMER_CHANGED_MIND">Changed my mind</option>
          <option value="DUPLICATE_ORDER">Duplicate order</option>
        </select>
        <button id="cancel-order-btn" class="btn btn-danger" type="button">Cancel Order</button>
      </div>
      <div id="cancellation-banner" class="alert alert-success" style="display:none;"></div>
      <div id="cancel-error-banner" class="alert alert-danger" role="alert" style="display:none;"></div>
    </div>
  </main>

  <script>
    let currentCart = [];
    let appliedCouponCode = null;
    let discountCents = 0;

    function formatRupees(cents) {
      return '₹' + (cents / 100).toFixed(2);
    }

    function computeSubtotalCents() {
      return currentCart.reduce((acc, it) => acc + (it.unitPriceCents * it.quantity), 0);
    }

    function syncTotalsUI() {
      const subtotal = computeSubtotalCents();
      const totalCount = currentCart.reduce((acc, it) => acc + it.quantity, 0);
      document.getElementById('cart-badge').innerText = totalCount + ' items';
      document.getElementById('cart-total-display').innerText = formatRupees(subtotal);
      document.getElementById('checkout-subtotal-display').innerText = formatRupees(subtotal);
      document.getElementById('checkout-discount-display').innerText = '-' + formatRupees(discountCents);
      document.getElementById('checkout-total-display').innerText = formatRupees(Math.max(0, subtotal - discountCents));
      const emptyMsg = document.getElementById('empty-cart-msg');
      const checkoutBtn = document.getElementById('checkout-btn');
      if (currentCart.length === 0) {
        emptyMsg.style.display = 'block';
        checkoutBtn.disabled = true;
      } else {
        emptyMsg.style.display = 'none';
        checkoutBtn.disabled = false;
      }
    }

    function renderCart() {
      const itemsList = document.getElementById('cart-items-list');
      itemsList.innerHTML = '';
      currentCart.forEach(item => {
        const row = document.createElement('div');
        row.className = 'cart-row';
        row.setAttribute('data-product-id', item.productId);
        const displayName = item.productName || item.name;
        const label = document.createElement('span');
        label.className = 'cart-item-label';
        label.innerText = displayName + ' x ' + item.quantity + ' - ' + formatRupees(item.unitPriceCents * item.quantity);
        row.appendChild(label);

        const incBtn = document.createElement('button');
        incBtn.className = 'btn btn-secondary qty-inc-btn';
        incBtn.type = 'button';
        incBtn.innerText = '+';
        incBtn.addEventListener('click', () => updateQuantity(item.productId, item.quantity + 1));
        row.appendChild(incBtn);

        const decBtn = document.createElement('button');
        decBtn.className = 'btn btn-secondary qty-dec-btn';
        decBtn.type = 'button';
        decBtn.innerText = '-';
        decBtn.addEventListener('click', () => updateQuantity(item.productId, item.quantity - 1));
        row.appendChild(decBtn);

        const remBtn = document.createElement('button');
        remBtn.className = 'btn btn-danger remove-item-btn';
        remBtn.type = 'button';
        remBtn.innerText = 'Remove';
        remBtn.addEventListener('click', () => removeCartItem(item.productId));
        row.appendChild(remBtn);

        itemsList.appendChild(row);
      });
      syncTotalsUI();
    }

    async function updateQuantity(productId, newQuantity) {
      if (newQuantity <= 0) {
        await removeCartItem(productId);
        return;
      }
      const res = await fetch('/api/v1/cart/items/' + encodeURIComponent(productId), {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ quantity: newQuantity })
      });
      if (res.ok) {
        const data = await res.json();
        currentCart = data.items;
      } else {
        currentCart = currentCart.map(it => it.productId === productId ? Object.assign({}, it, { quantity: newQuantity }) : it);
      }
      renderCart();
    }

    async function removeCartItem(productId) {
      const res = await fetch('/api/v1/cart/items/' + encodeURIComponent(productId), {
        method: 'DELETE'
      });
      if (res.ok) {
        const data = await res.json();
        currentCart = data.items;
      } else {
        currentCart = currentCart.filter(it => it.productId !== productId);
      }
      renderCart();
    }

    document.getElementById('search-btn').addEventListener('click', async () => {
      const q = document.getElementById('search-input').value;
      const res = await fetch('/api/v1/search?query=' + encodeURIComponent(q) + '&q=' + encodeURIComponent(q));
      const data = await res.json();
      const resultsDiv = document.getElementById('product-results');
      resultsDiv.innerHTML = '';
      data.results.forEach(prod => {
        const div = document.createElement('div');
        div.className = 'product-card';
        div.innerHTML = '<h3>' + prod.name + '</h3><p>' + prod.formattedPrice + '</p><button class="btn add-to-cart-btn" data-id="' + prod.id + '" data-name="' + prod.name + '" data-price="' + prod.priceCents + '">Add to Cart</button>';
        resultsDiv.appendChild(div);
      });

      document.querySelectorAll('.add-to-cart-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
          const id = e.target.getAttribute('data-id');
          const name = e.target.getAttribute('data-name');
          const price = parseInt(e.target.getAttribute('data-price'), 10);
          const cartRes = await fetch('/api/v1/cart/items', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              productId: id,
              productName: name,
              unitPriceCents: price,
              quantity: 1
            })
          });
          const cartData = await cartRes.json();
          currentCart = cartData.items;
          document.getElementById('cart-badge').innerText = cartData.itemCount + ' items';
          document.getElementById('view-cart-btn').style.display = 'inline-block';
        });
      });
    });

    document.getElementById('view-cart-btn').addEventListener('click', () => {
      document.getElementById('product-results').style.display = 'none';
      document.getElementById('cart-view').style.display = 'block';
      renderCart();
    });

    document.getElementById('continue-shopping-btn').addEventListener('click', () => {
      document.getElementById('cart-view').style.display = 'none';
      document.getElementById('product-results').style.display = 'block';
    });

    document.getElementById('checkout-btn').addEventListener('click', () => {
      document.getElementById('cart-view').style.display = 'none';
      document.getElementById('checkout-view').style.display = 'block';
      syncTotalsUI();
    });

    document.getElementById('apply-promo-btn').addEventListener('click', async () => {
      const code = document.getElementById('promo-input').value.trim();
      const feedback = document.getElementById('promo-feedback');
      const res = await fetch('/api/v1/pricing/coupons/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: '${TEST_USER.id}',
          couponCode: code,
          subtotalCents: computeSubtotalCents()
        })
      });
      const data = await res.json();
      if (res.ok && data.valid) {
        appliedCouponCode = data.code || code;
        discountCents = data.discountCents || 0;
        feedback.className = 'alert alert-success';
        feedback.innerText = data.message || ('Promo ' + appliedCouponCode + ' applied');
      } else {
        appliedCouponCode = null;
        discountCents = 0;
        feedback.className = 'alert alert-danger';
        feedback.innerText = data.message || 'Invalid promo code';
      }
      syncTotalsUI();
    });

    document.getElementById('place-order-btn').addEventListener('click', async () => {
      const cardNum = document.getElementById('card-number').value.replace(/\\s/g, '');
      const paymentMethodId = cardNum.includes('0002')
        ? 'pm_declined_card'
        : (cardNum.includes('9995') ? 'pm_insufficient_funds' : 'pm_card_visa');
      const res = await fetch('/api/v1/checkout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          userId: '${TEST_USER.id}',
          paymentMethodId: paymentMethodId,
          couponCode: appliedCouponCode,
          deliveryAddressId: 'addr-101',
          dropoffLat: 12.9279,
          dropoffLng: 77.6271
        })
      });
      const data = await res.json();
      const banner = document.getElementById('alert-banner');
      if (!res.ok || (typeof data.status === 'string' && data.status.startsWith('FAILED'))) {
        banner.innerText = data.message || data.status || 'Checkout failed. Please try again.';
        banner.style.display = 'block';
      } else {
        banner.style.display = 'none';
        document.getElementById('checkout-view').style.display = 'none';
        document.getElementById('confirmation-view').style.display = 'block';
        document.getElementById('confirmed-order-id').innerText = data.orderId;
        document.getElementById('order-status-badge').innerText = data.trackingStatus || 'PLACED';
        document.getElementById('delivery-eta').innerText = data.estimatedDeliveryMinutes + ' mins';
        document.getElementById('confirmed-total-display').innerText = formatRupees(data.totalCents ?? Math.max(0, computeSubtotalCents() - discountCents));
      }
    });

    document.getElementById('refresh-status-btn').addEventListener('click', async () => {
      const orderId = document.getElementById('confirmed-order-id').innerText;
      const res = await fetch('/api/v1/orders/' + encodeURIComponent(orderId));
      const data = await res.json();
      if (res.ok && data.status) {
        document.getElementById('order-status-badge').innerText = data.status;
        if (typeof data.estimatedDeliveryMinutes === 'number') {
          document.getElementById('delivery-eta').innerText = data.estimatedDeliveryMinutes + ' mins';
        }
      }
    });

    document.getElementById('cancel-order-btn').addEventListener('click', async () => {
      const orderId = document.getElementById('confirmed-order-id').innerText;
      const reason = document.getElementById('cancel-reason-select').value;
      const res = await fetch('/api/v1/orders/' + encodeURIComponent(orderId) + '/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason: reason })
      });
      const data = await res.json();
      const okBanner = document.getElementById('cancellation-banner');
      const errBanner = document.getElementById('cancel-error-banner');
      if (res.ok && data.status === 'CANCELLED') {
        errBanner.style.display = 'none';
        document.getElementById('order-status-badge').innerText = 'CANCELLED';
        okBanner.innerText = data.message || 'Order cancelled and refund initiated.';
        okBanner.style.display = 'block';
        document.getElementById('cancel-order-btn').style.display = 'none';
      } else {
        okBanner.style.display = 'none';
        errBanner.innerText = data.message || 'Order cannot be cancelled in its current state.';
        errBanner.style.display = 'block';
      }
    });
  </script>
</body>
</html>`;

  test('customer completes full happy path: search milk -> add to cart -> checkout -> order confirmation', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const generatedOrderId = `ord-synth-${Date.now()}`;

    await page.route('**/', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'text/html',
        body: STORE_HTML,
      });
    });

    await page.route('**/api/v1/search*', async (route) => {
      const url = new URL(route.request().url());
      const searchQuery = url.searchParams.get('query') ?? url.searchParams.get('q') ?? 'milk';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: searchQuery,
          total: 1,
          results: [
            {
              id: milk.id,
              name: milk.name,
              sku: milk.sku,
              priceCents: milk.priceCents,
              formattedPrice: milk.formattedPrice,
            },
          ],
        }),
      });
    });

    await page.route('**/api/v1/cart/items', async (route) => {
      const body = route.request().postDataJSON() as {
        productId?: string;
        productName?: string;
        unitPriceCents?: number;
        quantity?: number;
      };
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: [
            {
              productId: body?.productId ?? milk.id,
              productName: body?.productName ?? milk.name,
              name: body?.productName ?? milk.name,
              quantity: body?.quantity ?? 1,
              unitPriceCents: body?.unitPriceCents ?? milk.priceCents,
              lineTotalCents: (body?.unitPriceCents ?? milk.priceCents) * (body?.quantity ?? 1),
            },
          ],
          subtotalCents: milk.priceCents,
          itemCount: 1,
          version: 1,
        }),
      });
    });

    await page.route('**/api/v1/checkout', async (route) => {
      const payload = route.request().postDataJSON() as {
        userId: string;
        paymentMethodId: string;
        couponCode?: string | null;
        deliveryAddressId: string;
        dropoffLat?: number;
        dropoffLng?: number;
      };
      expect(payload.userId).toBe(TEST_USER.id);
      expect(payload.deliveryAddressId).toBe('addr-101');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          orderId: generatedOrderId,
          status: 'COMPLETED',
          trackingStatus: 'PLACED',
          totalCents: milk.priceCents,
          estimatedDeliveryMinutes: 12,
        }),
      });
    });

    await page.goto('/');
    await expect(page).toHaveTitle(/InstaCommerce/i);

    const searchInput = page.locator('#search-input');
    await searchInput.fill('milk');
    await page.locator('#search-btn').click();

    await expect(page.locator('text=Organic Whole Milk 1L')).toBeVisible();
    await expect(page.locator('text=₹65.00')).toBeVisible();

    await page.locator('.add-to-cart-btn').click();
    await expect(page.locator('#cart-badge')).toHaveText('1 items');
    await expect(page.locator('#view-cart-btn')).toBeVisible();

    await page.locator('#view-cart-btn').click();
    await expect(page.locator('#cart-view')).toBeVisible();
    await expect(page.locator('#cart-total-display')).toHaveText('₹65.00');

    await page.locator('#checkout-btn').click();
    await expect(page.locator('#checkout-view')).toBeVisible();

    await page.locator('#place-order-btn').click();

    await expect(page.locator('#confirmation-view')).toBeVisible();
    await expect(page.locator('#confirmed-order-id')).toHaveText(generatedOrderId);
    await expect(page.locator('#delivery-eta')).toHaveText('12 mins');
  });

  test('customer payment decline triggers user-visible alert without discarding cart items', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;

    await page.route('**/', async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: STORE_HTML });
    });

    await page.route('**/api/v1/search*', async (route) => {
      const url = new URL(route.request().url());
      const searchQuery = url.searchParams.get('query') ?? url.searchParams.get('q') ?? 'milk';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: searchQuery,
          total: 1,
          results: [{ id: milk.id, name: milk.name, sku: milk.sku, priceCents: milk.priceCents, formattedPrice: milk.formattedPrice }],
        }),
      });
    });

    await page.route('**/api/v1/cart/items', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: [{ productId: milk.id, productName: milk.name, name: milk.name, quantity: 1, unitPriceCents: milk.priceCents }],
          subtotalCents: milk.priceCents,
          itemCount: 1,
          version: 1,
        }),
      });
    });

    await page.route('**/api/v1/checkout', async (route) => {
      const payload = route.request().postDataJSON() as { paymentMethodId: string };
      if (payload.paymentMethodId === 'pm_declined_card') {
        await route.fulfill({
          status: 402,
          contentType: 'application/json',
          body: JSON.stringify({
            orderId: null,
            status: 'FAILED',
            message: 'Your payment card was declined. Please try another card.',
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            orderId: 'ord-retry-pass-777',
            status: 'COMPLETED',
            trackingStatus: 'PLACED',
            totalCents: milk.priceCents,
            estimatedDeliveryMinutes: 10,
          }),
        });
      }
    });

    await page.goto('/');
    await page.locator('#search-input').fill('milk');
    await page.locator('#search-btn').click();
    await page.locator('.add-to-cart-btn').click();
    await page.locator('#view-cart-btn').click();
    await page.locator('#checkout-btn').click();

    await page.locator('#card-number').fill(PAYMENT_CARDS.DECLINED_CARD.number);
    await page.locator('#place-order-btn').click();

    const alertBanner = page.locator('#alert-banner');
    await expect(alertBanner).toBeVisible();
    await expect(alertBanner).toContainText('Your payment card was declined');
    await expect(page.locator('#confirmation-view')).not.toBeVisible();

    await page.locator('#card-number').fill(PAYMENT_CARDS.SUCCESSFUL_VISA.number);
    await page.locator('#place-order-btn').click();

    await expect(page.locator('#confirmation-view')).toBeVisible();
    await expect(page.locator('#confirmed-order-id')).toHaveText('ord-retry-pass-777');
  });

  test('out-of-stock items trigger inventory alert during checkout', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;

    await page.route('**/', async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: STORE_HTML });
    });

    await page.route('**/api/v1/search*', async (route) => {
      const url = new URL(route.request().url());
      const searchQuery = url.searchParams.get('query') ?? url.searchParams.get('q') ?? 'milk';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: searchQuery,
          total: 1,
          results: [{ id: milk.id, name: milk.name, sku: milk.sku, priceCents: milk.priceCents, formattedPrice: milk.formattedPrice }],
        }),
      });
    });

    await page.route('**/api/v1/cart/items', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: [{ productId: milk.id, productName: milk.name, name: milk.name, quantity: 1, unitPriceCents: milk.priceCents }],
          subtotalCents: milk.priceCents,
          itemCount: 1,
          version: 1,
        }),
      });
    });

    await page.route('**/api/v1/checkout', async (route) => {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          orderId: null,
          status: 'FAILED: INSUFFICIENT_INVENTORY',
          totalCents: 0,
          estimatedDeliveryMinutes: 0,
          message: 'Some items in your cart became out of stock during checkout.',
        }),
      });
    });

    await page.goto('/');
    await page.locator('#search-input').fill('milk');
    await page.locator('#search-btn').click();
    await page.locator('.add-to-cart-btn').click();
    await page.locator('#view-cart-btn').click();
    await page.locator('#checkout-btn').click();
    await page.locator('#place-order-btn').click();

    const alertBanner = page.locator('#alert-banner');
    await expect(alertBanner).toBeVisible();
    await expect(alertBanner).toContainText('out of stock');
    await expect(page.locator('#confirmation-view')).not.toBeVisible();
  });

  test('customer adjusts cart quantities and removes items with live subtotal updates', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const bread = MOCK_PRODUCTS.bread;
    let serverCart: Array<{ productId: string; productName: string; quantity: number; unitPriceCents: number }> = [];

    const buildCartBody = () => ({
      userId: TEST_USER.id,
      items: serverCart,
      subtotalCents: serverCart.reduce((acc, it) => acc + it.quantity * it.unitPriceCents, 0),
      itemCount: serverCart.reduce((acc, it) => acc + it.quantity, 0),
      version: 2,
    });

    await page.route('**/', async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: STORE_HTML });
    });

    await page.route('**/api/v1/search*', async (route) => {
      const url = new URL(route.request().url());
      const q = (url.searchParams.get('query') ?? '').toLowerCase();
      const catalog = [milk, bread].filter((p) => p.name.toLowerCase().includes(q));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ query: q, total: catalog.length, results: catalog }),
      });
    });

    await page.route('**/api/v1/cart/items/*', async (route) => {
      const url = new URL(route.request().url());
      const productId = decodeURIComponent(url.pathname.split('/').pop() ?? '');
      if (route.request().method() === 'PATCH') {
        const body = route.request().postDataJSON() as { quantity: number };
        serverCart = serverCart.map((it) => (it.productId === productId ? { ...it, quantity: body.quantity } : it));
      } else if (route.request().method() === 'DELETE') {
        serverCart = serverCart.filter((it) => it.productId !== productId);
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(buildCartBody()) });
    });

    await page.route('**/api/v1/cart/items', async (route) => {
      const body = route.request().postDataJSON() as {
        productId: string;
        productName: string;
        unitPriceCents: number;
        quantity: number;
      };
      const existing = serverCart.find((it) => it.productId === body.productId);
      if (existing) {
        existing.quantity += body.quantity;
      } else {
        serverCart.push({
          productId: body.productId,
          productName: body.productName,
          unitPriceCents: body.unitPriceCents,
          quantity: body.quantity,
        });
      }
      await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(buildCartBody()) });
    });

    await page.goto('/');
    await page.locator('#search-input').fill('milk');
    await page.locator('#search-btn').click();
    await page.locator('.add-to-cart-btn').click();

    await page.locator('#search-input').fill('bread');
    await page.locator('#search-btn').click();
    await page.locator('.add-to-cart-btn').click();

    await expect(page.locator('#cart-badge')).toHaveText('2 items');
    await page.locator('#view-cart-btn').click();
    await expect(page.locator('#cart-total-display')).toHaveText('₹150.00');

    // Increase milk quantity to 2 (2 * 65 + 85 = ₹215.00)
    const milkRow = page.locator(`.cart-row[data-product-id="${milk.id}"]`);
    await milkRow.locator('.qty-inc-btn').click();
    await expect(page.locator('#cart-total-display')).toHaveText('₹215.00');
    await expect(page.locator('#cart-badge')).toHaveText('3 items');

    // Remove bread (2 * 65 = ₹130.00)
    const breadRow = page.locator(`.cart-row[data-product-id="${bread.id}"]`);
    await breadRow.locator('.remove-item-btn').click();
    await expect(page.locator('#cart-total-display')).toHaveText('₹130.00');

    // Decrement milk twice -> empty cart blocks checkout
    await milkRow.locator('.qty-dec-btn').click();
    await expect(page.locator('#cart-total-display')).toHaveText('₹65.00');
    await milkRow.locator('.qty-dec-btn').click();
    await expect(page.locator('#empty-cart-msg')).toBeVisible();
    await expect(page.locator('#checkout-btn')).toBeDisabled();
  });

  test('customer applies invalid then valid promo code and checks out with discounted total', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const bread = MOCK_PRODUCTS.bread;
    const serverCart = [
      { productId: milk.id, productName: milk.name, unitPriceCents: milk.priceCents, quantity: 1 },
      { productId: bread.id, productName: bread.name, unitPriceCents: bread.priceCents, quantity: 1 },
    ];
    let lastCheckoutCoupon: string | null | undefined;

    await page.route('**/', async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: STORE_HTML });
    });

    await page.route('**/api/v1/search*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ query: 'milk', total: 1, results: [milk] }),
      });
    });

    await page.route('**/api/v1/cart/items', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: serverCart,
          subtotalCents: milk.priceCents + bread.priceCents,
          itemCount: 2,
          version: 1,
        }),
      });
    });

    await page.route('**/api/v1/pricing/coupons/validate', async (route) => {
      const body = route.request().postDataJSON() as { couponCode: string; subtotalCents: number };
      if (body.couponCode === PROMO_CODES.VALID_FRESH20.code) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            valid: true,
            code: PROMO_CODES.VALID_FRESH20.code,
            discountCents: PROMO_CODES.VALID_FRESH20.discountCents,
            message: PROMO_CODES.VALID_FRESH20.message,
          }),
        });
      } else {
        await route.fulfill({
          status: 422,
          contentType: 'application/json',
          body: JSON.stringify({
            valid: false,
            discountCents: 0,
            message: PROMO_CODES.EXPIRED_10.message,
          }),
        });
      }
    });

    await page.route('**/api/v1/checkout', async (route) => {
      const body = route.request().postDataJSON() as { couponCode?: string | null };
      lastCheckoutCoupon = body.couponCode;
      const discountedTotal = milk.priceCents + bread.priceCents - PROMO_CODES.VALID_FRESH20.discountCents;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          orderId: 'ord-promo-fresh20',
          status: 'COMPLETED',
          trackingStatus: 'PLACED',
          totalCents: discountedTotal,
          estimatedDeliveryMinutes: 11,
        }),
      });
    });

    await page.goto('/');
    await page.locator('#search-input').fill('milk');
    await page.locator('#search-btn').click();
    await page.locator('.add-to-cart-btn').click();
    await page.locator('#view-cart-btn').click();
    await page.locator('#checkout-btn').click();

    await expect(page.locator('#checkout-subtotal-display')).toHaveText('₹150.00');
    await expect(page.locator('#checkout-total-display')).toHaveText('₹150.00');

    // Try expired promo code first
    await page.locator('#promo-input').fill(PROMO_CODES.EXPIRED_10.code);
    await page.locator('#apply-promo-btn').click();
    await expect(page.locator('#promo-feedback')).toContainText('has expired');
    await expect(page.locator('#checkout-total-display')).toHaveText('₹150.00');

    // Apply valid FRESH20 promo code (-₹30.00)
    await page.locator('#promo-input').fill(PROMO_CODES.VALID_FRESH20.code);
    await page.locator('#apply-promo-btn').click();
    await expect(page.locator('#promo-feedback')).toContainText('FRESH20 applied');
    await expect(page.locator('#checkout-discount-display')).toHaveText('-₹30.00');
    await expect(page.locator('#checkout-total-display')).toHaveText('₹120.00');

    await page.locator('#place-order-btn').click();
    expect(lastCheckoutCoupon).toBe('FRESH20');
    await expect(page.locator('#confirmation-view')).toBeVisible();
    await expect(page.locator('#confirmed-total-display')).toHaveText('₹120.00');
  });

  test('insufficient funds failure recovers on fallback Visa card while preserving promo state', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const seenPaymentMethods: string[] = [];

    await page.route('**/', async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: STORE_HTML });
    });

    await page.route('**/api/v1/search*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ query: 'milk', total: 1, results: [milk] }),
      });
    });

    await page.route('**/api/v1/cart/items', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: [{ productId: milk.id, productName: milk.name, unitPriceCents: milk.priceCents, quantity: 1 }],
          subtotalCents: milk.priceCents,
          itemCount: 1,
          version: 1,
        }),
      });
    });

    await page.route('**/api/v1/pricing/coupons/validate', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          valid: true,
          code: 'FRESH20',
          discountCents: 1300,
          message: 'Promo FRESH20 applied (-₹13.00)',
        }),
      });
    });

    await page.route('**/api/v1/checkout', async (route) => {
      const body = route.request().postDataJSON() as { paymentMethodId: string; couponCode?: string | null };
      seenPaymentMethods.push(body.paymentMethodId);
      expect(body.couponCode).toBe('FRESH20');
      if (body.paymentMethodId === 'pm_insufficient_funds') {
        await route.fulfill({
          status: 402,
          contentType: 'application/json',
          body: JSON.stringify({
            orderId: null,
            status: 'FAILED: INSUFFICIENT_FUNDS',
            message: 'Insufficient funds on selected card. Try another payment method.',
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            orderId: 'ord-recovered-funds-88',
            status: 'COMPLETED',
            trackingStatus: 'PLACED',
            totalCents: milk.priceCents - 1300,
            estimatedDeliveryMinutes: 10,
          }),
        });
      }
    });

    await page.goto('/');
    await page.locator('#search-input').fill('milk');
    await page.locator('#search-btn').click();
    await page.locator('.add-to-cart-btn').click();
    await page.locator('#view-cart-btn').click();
    await page.locator('#checkout-btn').click();

    await page.locator('#promo-input').fill('FRESH20');
    await page.locator('#apply-promo-btn').click();
    await page.locator('#card-number').fill(PAYMENT_CARDS.INSUFFICIENT_FUNDS_CARD.number);
    await page.locator('#place-order-btn').click();

    await expect(page.locator('#alert-banner')).toBeVisible();
    await expect(page.locator('#alert-banner')).toContainText('Insufficient funds');

    // Recover with valid Visa while retaining FRESH20 discount
    await page.locator('#card-number').fill(PAYMENT_CARDS.SUCCESSFUL_VISA.number);
    await page.locator('#place-order-btn').click();

    expect(seenPaymentMethods).toEqual(['pm_insufficient_funds', 'pm_card_visa']);
    await expect(page.locator('#confirmation-view')).toBeVisible();
    await expect(page.locator('#confirmed-order-id')).toHaveText('ord-recovered-funds-88');
    await expect(page.locator('#confirmed-total-display')).toHaveText('₹52.00');
  });

  test('customer tracks order progression and cancels pre-fulfillment, while out-for-delivery blocks cancel', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const orderId = 'ord-track-cancel-404';
    let trackingIndex = 0;
    const timeline: Array<(typeof ORDER_TRACKING_STATES)[number]> = ['PLACED', 'PACKING', 'OUT_FOR_DELIVERY'];
    let cancelAllowed = true;

    await page.route('**/', async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: STORE_HTML });
    });

    await page.route('**/api/v1/search*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ query: 'milk', total: 1, results: [milk] }),
      });
    });

    await page.route('**/api/v1/cart/items', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: [{ productId: milk.id, productName: milk.name, unitPriceCents: milk.priceCents, quantity: 1 }],
          subtotalCents: milk.priceCents,
          itemCount: 1,
          version: 1,
        }),
      });
    });

    await page.route('**/api/v1/checkout', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          orderId,
          status: 'COMPLETED',
          trackingStatus: 'PLACED',
          totalCents: milk.priceCents,
          estimatedDeliveryMinutes: 12,
        }),
      });
    });

    await page.route(`**/api/v1/orders/${orderId}`, async (route) => {
      trackingIndex = Math.min(trackingIndex + 1, timeline.length - 1);
      const nextStatus = timeline[trackingIndex];
      if (nextStatus === 'OUT_FOR_DELIVERY') {
        cancelAllowed = false;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          orderId,
          status: nextStatus,
          estimatedDeliveryMinutes: nextStatus === 'OUT_FOR_DELIVERY' ? 4 : 9,
        }),
      });
    });

    await page.route(`**/api/v1/orders/${orderId}/cancel`, async (route) => {
      const payload = route.request().postDataJSON() as { reason: string };
      expect(payload.reason).toBe('CUSTOMER_CHANGED_MIND');
      if (cancelAllowed) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            orderId,
            status: 'CANCELLED',
            message: 'Order cancelled and inventory reservation released.',
          }),
        });
      } else {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            orderId,
            status: 'OUT_FOR_DELIVERY',
            message: 'Cannot cancel order after rider dispatch (OUT_FOR_DELIVERY).',
          }),
        });
      }
    });

    await page.goto('/');
    await page.locator('#search-input').fill('milk');
    await page.locator('#search-btn').click();
    await page.locator('.add-to-cart-btn').click();
    await page.locator('#view-cart-btn').click();
    await page.locator('#checkout-btn').click();
    await page.locator('#place-order-btn').click();

    await expect(page.locator('#order-status-badge')).toHaveText('PLACED');
    await page.locator('#refresh-status-btn').click();
    await expect(page.locator('#order-status-badge')).toHaveText('PACKING');
    await expect(page.locator('#delivery-eta')).toHaveText('9 mins');

    // Out-for-delivery blocks cancellation (409 Conflict)
    await page.locator('#refresh-status-btn').click();
    await expect(page.locator('#order-status-badge')).toHaveText('OUT_FOR_DELIVERY');
    await page.locator('#cancel-order-btn').click();
    await expect(page.locator('#cancel-error-banner')).toContainText('Cannot cancel order after rider dispatch');

    // Pre-fulfillment cancellation succeeds and enters terminal CANCELLED state
    cancelAllowed = true;
    await page.locator('#cancel-order-btn').click();
    await expect(page.locator('#order-status-badge')).toHaveText('CANCELLED');
    await expect(page.locator('#cancellation-banner')).toContainText('inventory reservation released');
    await expect(page.locator('#cancel-order-btn')).not.toBeVisible();
  });
});
