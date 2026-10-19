# InstaCommerce Playwright Synthetic End-to-End Suite

Synthetic browser checkout and customer journey test suite automated via Playwright.

## Test Coverage
- **Customer Shopping Journey**: Search for items (e.g. `milk`), add to cart, review cart line items, enter delivery address, enter sandbox test cards, execute checkout, and verify order confirmation with real-time ETA.
- **Payment Decline & Card Retry**: Verifies that when a card is declined (e.g., Stripe test card `4000 0000 0000 0002`), customer is informed via alert and cart state is preserved without data loss for card retry.
- **Inventory Out-of-Stock**: Verifies that when stock depletion occurs concurrently, the customer receives an actionable warning rather than an unhandled 500 server error.

## Running Tests Locally

```bash
cd tests/e2e
npm install
npx playwright install --with-deps chromium

# Run all synthetic browser tests
npm test

# Run in interactive UI mode
npm run test:ui

# Run against a specific staging environment
STAGING_URL=https://staging.instacommerce.internal npm test
```

## CI Integration
Configured as an automated gate in `.github/workflows/ci.yml` before production promotion.
