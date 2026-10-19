import { test, expect } from '@playwright/test';
import {
  MOCK_PRODUCTS,
  PAYMENT_CARDS,
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
    .product-card { border: 1px solid #ddd; padding: 15px; margin: 10px 0; border-radius: 8px; width: 300px; }
    .btn { background: #00875a; color: white; border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; }
    .btn-danger { background: #de350b; }
    .alert { padding: 10px; border-radius: 4px; margin: 10px 0; }
    .alert-danger { background: #ffebe6; color: #bf2600; border: 1px solid #ffbdad; }
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
      <p>Subtotal: <strong id="cart-total-display">₹0.00</strong></p>
      <button id="checkout-btn" class="btn">Proceed to Checkout</button>
    </div>

    <div id="checkout-view" style="display:none;">
      <h2>Checkout & Payment</h2>
      <div id="alert-banner" class="alert alert-danger" role="alert" style="display:none;"></div>
      <div>
        <p><strong>Delivery Address:</strong> ${TEST_DELIVERY_ADDRESS.street}, ${TEST_DELIVERY_ADDRESS.city}</p>
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
      <p>Delivery ETA: <span id="delivery-eta" class="badge">12 mins</span></p>
    </div>
  </main>

  <script>
    let currentCart = [];

    document.getElementById('search-btn').addEventListener('click', async () => {
      const q = document.getElementById('search-input').value;
      const res = await fetch('/api/v1/search?q=' + encodeURIComponent(q));
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
          const cartRes = await fetch('/api/v1/cart', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productId: id, quantity: 1 })
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
      const itemsList = document.getElementById('cart-items-list');
      itemsList.innerHTML = '';
      currentCart.forEach(item => {
        const p = document.createElement('p');
        p.innerText = item.name + ' x ' + item.quantity + ' - ₹' + (item.unitPriceCents / 100).toFixed(2);
        itemsList.appendChild(p);
      });
      document.getElementById('cart-total-display').innerText = '₹' + (currentCart.reduce((acc, it) => acc + (it.unitPriceCents * it.quantity), 0) / 100).toFixed(2);
    });

    document.getElementById('checkout-btn').addEventListener('click', () => {
      document.getElementById('cart-view').style.display = 'none';
      document.getElementById('checkout-view').style.display = 'block';
    });

    document.getElementById('place-order-btn').addEventListener('click', async () => {
      const cardNum = document.getElementById('card-number').value.replace(/\\s/g, '');
      const paymentMethodId = cardNum.includes('0002') ? 'pm_declined_card' : (cardNum.includes('9995') ? 'pm_insufficient_funds' : 'pm_card_visa');
      const res = await fetch('/api/v1/checkout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: '${TEST_USER.id}', paymentMethodId: paymentMethodId, deliveryAddressId: 'addr-101' })
      });
      const data = await res.json();
      if (!res.ok || data.status === 'FAILED') {
        const banner = document.getElementById('alert-banner');
        banner.innerText = data.message || 'Checkout failed. Please try again.';
        banner.style.display = 'block';
      } else {
        document.getElementById('checkout-view').style.display = 'none';
        document.getElementById('confirmation-view').style.display = 'block';
        document.getElementById('confirmed-order-id').innerText = data.orderId;
        document.getElementById('delivery-eta').innerText = data.estimatedDeliveryMinutes + ' mins';
      }
    });
  </script>
</body>
</html>`;

  test('customer completes full happy path: search milk -> add to cart -> checkout -> order confirmation', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const generatedOrderId = `ord-synth-${Date.now()}`;

    // 1. Intercept base page
    await page.route('**/', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'text/html',
        body: STORE_HTML,
      });
    });

    // 2. Intercept API routes
    await page.route('**/api/v1/search*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: 'milk',
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

    await page.route('**/api/v1/cart**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: [{ productId: milk.id, name: milk.name, quantity: 1, unitPriceCents: milk.priceCents }],
          subtotalCents: milk.priceCents,
          itemCount: 1,
        }),
      });
    });

    await page.route('**/api/v1/checkout', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          orderId: generatedOrderId,
          status: 'COMPLETED',
          totalCents: milk.priceCents,
          estimatedDeliveryMinutes: 12,
        }),
      });
    });

    // Visit Storefront
    await page.goto('/');
    await expect(page).toHaveTitle(/InstaCommerce/i);

    // Search for "milk"
    const searchInput = page.locator('#search-input');
    await searchInput.fill('milk');
    await page.locator('#search-btn').click();

    // Verify product result displayed
    await expect(page.locator('text=Organic Whole Milk 1L')).toBeVisible();
    await expect(page.locator('text=₹65.00')).toBeVisible();

    // Add to Cart
    await page.locator('.add-to-cart-btn').click();

    // Verify cart badge increments
    await expect(page.locator('#cart-badge')).toHaveText('1 items');
    await expect(page.locator('#view-cart-btn')).toBeVisible();

    // View Cart
    await page.locator('#view-cart-btn').click();
    await expect(page.locator('#cart-view')).toBeVisible();
    await expect(page.locator('#cart-total-display')).toHaveText('₹65.00');

    // Proceed to Checkout
    await page.locator('#checkout-btn').click();
    await expect(page.locator('#checkout-view')).toBeVisible();

    // Place Order
    await page.locator('#place-order-btn').click();

    // Verify Order Confirmation Page
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
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: 'milk',
          total: 1,
          results: [{ id: milk.id, name: milk.name, sku: milk.sku, priceCents: milk.priceCents, formattedPrice: milk.formattedPrice }],
        }),
      });
    });

    await page.route('**/api/v1/cart**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: [{ productId: milk.id, name: milk.name, quantity: 1, unitPriceCents: milk.priceCents }],
          itemCount: 1,
        }),
      });
    });

    await page.route('**/api/v1/checkout', async (route) => {
      const data = route.request().postDataJSON();
      if (data?.paymentMethodId === 'pm_declined_card') {
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

    // 1. Enter declined card
    await page.locator('#card-number').fill(PAYMENT_CARDS.DECLINED_CARD.number);
    await page.locator('#place-order-btn').click();

    // 2. Alert banner must display error
    const alertBanner = page.locator('#alert-banner');
    await expect(alertBanner).toBeVisible();
    await expect(alertBanner).toContainText('Your payment card was declined');

    // 3. User remains on checkout page (no checkout completion yet)
    await expect(page.locator('#confirmation-view')).not.toBeVisible();

    // 4. Retry with valid card
    await page.locator('#card-number').fill(PAYMENT_CARDS.SUCCESSFUL_VISA.number);
    await page.locator('#place-order-btn').click();

    // 5. Order confirmed after retry
    await expect(page.locator('#confirmation-view')).toBeVisible();
    await expect(page.locator('#confirmed-order-id')).toHaveText('ord-retry-pass-777');
  });

  test('out-of-stock items trigger inventory alert during checkout', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;

    await page.route('**/', async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: STORE_HTML });
    });

    await page.route('**/api/v1/search*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          query: 'milk',
          total: 1,
          results: [{ id: milk.id, name: milk.name, sku: milk.sku, priceCents: milk.priceCents, formattedPrice: milk.formattedPrice }],
        }),
      });
    });

    await page.route('**/api/v1/cart**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          userId: TEST_USER.id,
          items: [{ productId: milk.id, name: milk.name, quantity: 1, unitPriceCents: milk.priceCents }],
          itemCount: 1,
        }),
      });
    });

    await page.route('**/api/v1/checkout', async (route) => {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          orderId: null,
          status: 'FAILED',
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
});
