import fs from 'node:fs';
import path from 'node:path';
import type { Page, Route } from '@playwright/test';
import {
  MOCK_PRODUCTS,
  PAYMENT_CARDS,
  TEST_DELIVERY_ADDRESS,
  TEST_USER,
  type TestProduct,
} from './mock-data.js';

const STORE_HTML_TEMPLATE = fs.readFileSync(
  path.resolve(__dirname, 'store-app.html'),
  'utf8',
);

export const STORE_HTML = STORE_HTML_TEMPLATE
  .replaceAll('{{DELIVERY_ADDRESS}}', `${TEST_DELIVERY_ADDRESS.street}, ${TEST_DELIVERY_ADDRESS.city}`)
  .replaceAll('{{DEFAULT_CARD_NUMBER}}', PAYMENT_CARDS.SUCCESSFUL_VISA.number)
  .replaceAll('{{TEST_USER_ID}}', TEST_USER.id);

export interface CartItemPayload {
  productId: string;
  productName: string;
  unitPriceCents: number;
  quantity: number;
}

export interface CheckoutPayload {
  userId: string;
  paymentMethodId: string;
  couponCode?: string | null;
  deliveryAddressId: string;
  dropoffLat?: number;
  dropoffLng?: number;
}

export interface MockRoutesOptions {
  products?: TestProduct[];
  initialCart?: CartItemPayload[];
  useStatefulCart?: boolean;
  onSearch?: (query: string, route: Route) => Promise<void>;
  onAddToCart?: (body: Partial<CartItemPayload>, route: Route) => Promise<void>;
  onValidateCoupon?: (body: { couponCode: string; subtotalCents: number }, route: Route) => Promise<void>;
  onCheckout?: (body: CheckoutPayload, route: Route) => Promise<void>;
  orderId?: string;
  onGetOrder?: (route: Route) => Promise<void>;
  onCancelOrder?: (body: { reason: string }, route: Route) => Promise<void>;
}

export async function setupStoreRoutes(page: Page, options: MockRoutesOptions = {}): Promise<void> {
  const catalog = options.products ?? [MOCK_PRODUCTS.milk];
  let serverCart: CartItemPayload[] = options.initialCart ? options.initialCart.map((it) => ({ ...it })) : [];

  const buildCartBody = () => ({
    userId: TEST_USER.id,
    items: serverCart,
    subtotalCents: serverCart.reduce((acc, it) => acc + it.quantity * it.unitPriceCents, 0),
    itemCount: serverCart.reduce((acc, it) => acc + it.quantity, 0),
    version: 1,
  });

  await page.route('**/', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'text/html',
      body: STORE_HTML,
    });
  });

  await page.route('**/api/v1/search*', async (route) => {
    const url = new URL(route.request().url());
    const searchQuery = url.searchParams.get('query') ?? '';
    if (options.onSearch) {
      await options.onSearch(searchQuery, route);
      return;
    }
    const normalized = searchQuery.trim().toLowerCase();
    const matched = normalized
      ? catalog.filter((p) => p.name.toLowerCase().includes(normalized))
      : catalog;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ query: searchQuery, total: matched.length, results: matched }),
    });
  });

  if (options.useStatefulCart) {
    await page.route('**/api/v1/cart/items/*', async (route) => {
      const url = new URL(route.request().url());
      const productId = decodeURIComponent(url.pathname.split('/').pop() ?? '');
      if (route.request().method() === 'PATCH') {
        const body = route.request().postDataJSON() as { quantity: number };
        serverCart = serverCart.map((it) => (it.productId === productId ? { ...it, quantity: body.quantity } : it));
      } else if (route.request().method() === 'DELETE') {
        serverCart = serverCart.filter((it) => it.productId !== productId);
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(buildCartBody()),
      });
    });
  }

  await page.route('**/api/v1/cart/items', async (route) => {
    const body = (route.request().postDataJSON() ?? {}) as Partial<CartItemPayload>;
    if (options.onAddToCart) {
      await options.onAddToCart(body, route);
      return;
    }
    const fallbackProduct = catalog[0] ?? MOCK_PRODUCTS.milk;
    const item: CartItemPayload = {
      productId: body.productId ?? fallbackProduct.id,
      productName: body.productName ?? fallbackProduct.name,
      unitPriceCents: body.unitPriceCents ?? fallbackProduct.priceCents,
      quantity: body.quantity ?? 1,
    };
    if (options.useStatefulCart) {
      const existing = serverCart.find((it) => it.productId === item.productId);
      if (existing) {
        existing.quantity += item.quantity;
      } else {
        serverCart.push(item);
      }
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(buildCartBody()),
      });
      return;
    }
    const items = options.initialCart ?? [item];
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        userId: TEST_USER.id,
        items,
        subtotalCents: items.reduce((acc, it) => acc + it.unitPriceCents * it.quantity, 0),
        itemCount: items.reduce((acc, it) => acc + it.quantity, 0),
        version: 1,
      }),
    });
  });

  if (options.onValidateCoupon) {
    const validateCoupon = options.onValidateCoupon;
    await page.route('**/api/v1/pricing/coupons/validate', async (route) => {
      const body = route.request().postDataJSON() as { couponCode: string; subtotalCents: number };
      await validateCoupon(body, route);
    });
  }

  if (options.onCheckout) {
    const handleCheckout = options.onCheckout;
    await page.route('**/api/v1/checkout', async (route) => {
      const body = route.request().postDataJSON() as CheckoutPayload;
      await handleCheckout(body, route);
    });
  }

  if (options.orderId && options.onGetOrder) {
    const handleGetOrder = options.onGetOrder;
    await page.route(`**/api/v1/orders/${options.orderId}`, async (route) => {
      await handleGetOrder(route);
    });
  }

  if (options.orderId && options.onCancelOrder) {
    const handleCancelOrder = options.onCancelOrder;
    await page.route(`**/api/v1/orders/${options.orderId}/cancel`, async (route) => {
      const body = route.request().postDataJSON() as { reason: string };
      await handleCancelOrder(body, route);
    });
  }
}

export async function addSingleProductAndOpenCheckout(page: Page, query = 'milk'): Promise<void> {
  await page.goto('/');
  await page.locator('#search-input').fill(query);
  await page.locator('#search-btn').click();
  await page.locator('.add-to-cart-btn').click();
  await page.locator('#view-cart-btn').click();
  await page.locator('#checkout-btn').click();
}
