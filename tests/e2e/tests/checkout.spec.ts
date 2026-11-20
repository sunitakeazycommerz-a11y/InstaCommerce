import { expect, test } from '@playwright/test';
import { MOCK_PRODUCTS, PROMO_CODES, TEST_USER } from '../fixtures/mock-data.js';
import { addSingleProductAndOpenCheckout, setupStoreRoutes } from '../fixtures/mock-routes.js';

test.describe('InstaCommerce Core Checkout & Cart Flows', () => {
  test('customer completes full happy path: search milk -> add to cart -> checkout -> order confirmation', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const generatedOrderId = `ord-synth-${Date.now()}`;

    await setupStoreRoutes(page, {
      onSearch: async (searchQuery, route) => {
        expect(searchQuery).toBe('milk');
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            query: searchQuery,
            total: 1,
            results: [milk],
          }),
        });
      },
      onCheckout: async (payload, route) => {
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
      },
    });

    await page.goto('/');
    await expect(page).toHaveTitle(/InstaCommerce/i);

    await page.locator('#search-input').fill('milk');
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

  test('customer adjusts cart quantities and removes items with live subtotal updates', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const bread = MOCK_PRODUCTS.bread;

    await setupStoreRoutes(page, {
      products: [milk, bread],
      useStatefulCart: true,
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

    const milkRow = page.locator(`.cart-row[data-product-id="${milk.id}"]`);
    await milkRow.locator('.qty-inc-btn').click();
    await expect(page.locator('#cart-total-display')).toHaveText('₹215.00');
    await expect(page.locator('#cart-badge')).toHaveText('3 items');

    const breadRow = page.locator(`.cart-row[data-product-id="${bread.id}"]`);
    await breadRow.locator('.remove-item-btn').click();
    await expect(page.locator('#cart-total-display')).toHaveText('₹130.00');

    await milkRow.locator('.qty-dec-btn').click();
    await expect(page.locator('#cart-total-display')).toHaveText('₹65.00');
    await milkRow.locator('.qty-dec-btn').click();
    await expect(page.locator('#empty-cart-msg')).toBeVisible();
    await expect(page.locator('#checkout-btn')).toBeDisabled();
  });

  test('customer applies invalid then valid promo code and checks out with discounted total', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const bread = MOCK_PRODUCTS.bread;
    let lastCheckoutCoupon: string | null | undefined;

    await setupStoreRoutes(page, {
      products: [milk],
      initialCart: [
        { productId: milk.id, productName: milk.name, unitPriceCents: milk.priceCents, quantity: 1 },
        { productId: bread.id, productName: bread.name, unitPriceCents: bread.priceCents, quantity: 1 },
      ],
      onValidateCoupon: async (body, route) => {
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
      },
      onCheckout: async (body, route) => {
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
      },
    });

    await addSingleProductAndOpenCheckout(page);

    await expect(page.locator('#checkout-subtotal-display')).toHaveText('₹150.00');
    await expect(page.locator('#checkout-total-display')).toHaveText('₹150.00');

    await page.locator('#promo-input').fill(PROMO_CODES.EXPIRED_10.code);
    await page.locator('#apply-promo-btn').click();
    await expect(page.locator('#promo-feedback')).toContainText('has expired');
    await expect(page.locator('#checkout-total-display')).toHaveText('₹150.00');

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
});
