import { expect, test } from '@playwright/test';
import { MOCK_PRODUCTS, ORDER_TRACKING_STATES, PAYMENT_CARDS } from '../fixtures/mock-data.js';
import { addSingleProductAndOpenCheckout, setupStoreRoutes } from '../fixtures/mock-routes.js';

test.describe('InstaCommerce Checkout Failure, Recovery & Lifecycle Edge Cases', () => {
  test('customer payment decline triggers user-visible alert without discarding cart items', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;

    await setupStoreRoutes(page, {
      onCheckout: async (payload, route) => {
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
      },
    });

    await addSingleProductAndOpenCheckout(page);

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
    await setupStoreRoutes(page, {
      onCheckout: async (_payload, route) => {
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
      },
    });

    await addSingleProductAndOpenCheckout(page);
    await page.locator('#place-order-btn').click();

    const alertBanner = page.locator('#alert-banner');
    await expect(alertBanner).toBeVisible();
    await expect(alertBanner).toContainText('out of stock');
    await expect(page.locator('#confirmation-view')).not.toBeVisible();
  });

  test('insufficient funds failure recovers on fallback Visa card while preserving promo state', async ({ page }) => {
    const milk = MOCK_PRODUCTS.milk;
    const seenPaymentMethods: string[] = [];

    await setupStoreRoutes(page, {
      onValidateCoupon: async (_body, route) => {
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
      },
      onCheckout: async (body, route) => {
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
      },
    });

    await addSingleProductAndOpenCheckout(page);

    await page.locator('#promo-input').fill('FRESH20');
    await page.locator('#apply-promo-btn').click();
    await page.locator('#card-number').fill(PAYMENT_CARDS.INSUFFICIENT_FUNDS_CARD.number);
    await page.locator('#place-order-btn').click();

    await expect(page.locator('#alert-banner')).toBeVisible();
    await expect(page.locator('#alert-banner')).toContainText('Insufficient funds');

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

    await setupStoreRoutes(page, {
      orderId,
      onCheckout: async (_body, route) => {
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
      },
      onGetOrder: async (route) => {
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
      },
      onCancelOrder: async (payload, route) => {
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
      },
    });

    await addSingleProductAndOpenCheckout(page);
    await page.locator('#place-order-btn').click();

    await expect(page.locator('#order-status-badge')).toHaveText('PLACED');
    await page.locator('#refresh-status-btn').click();
    await expect(page.locator('#order-status-badge')).toHaveText('PACKING');
    await expect(page.locator('#delivery-eta')).toHaveText('9 mins');

    await page.locator('#refresh-status-btn').click();
    await expect(page.locator('#order-status-badge')).toHaveText('OUT_FOR_DELIVERY');
    await page.locator('#cancel-order-btn').click();
    await expect(page.locator('#cancel-error-banner')).toContainText('Cannot cancel order after rider dispatch');

    cancelAllowed = true;
    await page.locator('#cancel-order-btn').click();
    await expect(page.locator('#order-status-badge')).toHaveText('CANCELLED');
    await expect(page.locator('#cancellation-banner')).toContainText('inventory reservation released');
    await expect(page.locator('#cancel-order-btn')).not.toBeVisible();
  });
});
