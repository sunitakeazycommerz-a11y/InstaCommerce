/**
 * Test fixtures and mock sandbox data for synthetic end-to-end checkout flows.
 */

export interface TestProduct {
  id: string;
  name: string;
  sku: string;
  priceCents: number;
  formattedPrice: string;
}

export interface TestAddress {
  fullName: string;
  phone: string;
  street: string;
  city: string;
  state: string;
  postalCode: string;
}

export interface TestCard {
  number: string;
  exp: string;
  cvc: string;
  nameOnCard: string;
}

export const TEST_USER = {
  id: 'usr-synth-e2e-001',
  email: 'synthetic.shopper@instacommerce.test',
  name: 'Synthetic Shopper',
  phone: '+919876543210',
};

export const MOCK_PRODUCTS: Record<string, TestProduct> = {
  milk: {
    id: 'prod-milk-organic-1l',
    name: 'Organic Whole Milk 1L',
    sku: 'MILK-ORG-1L',
    priceCents: 6500, // ₹65.00
    formattedPrice: '₹65.00',
  },
  bread: {
    id: 'prod-sourdough-bread',
    name: 'Artisan Sourdough Bread',
    sku: 'BREAD-SOUR-500G',
    priceCents: 8500, // ₹85.00
    formattedPrice: '₹85.00',
  },
};

export const TEST_DELIVERY_ADDRESS: TestAddress = {
  fullName: 'Synthetic Shopper',
  phone: '+919876543210',
  street: 'Tower 4, Flat 1204, Green Glen Layout, Outer Ring Road',
  city: 'Bengaluru',
  state: 'Karnataka',
  postalCode: '560103',
};

export const PAYMENT_CARDS = {
  // Successful standard Visa test card
  SUCCESSFUL_VISA: {
    number: '4242 4242 4242 4242',
    exp: '12/28',
    cvc: '123',
    nameOnCard: 'Synthetic Shopper',
  },
  // Card that triggers an intentional decline error in payment sandbox
  DECLINED_CARD: {
    number: '4000 0000 0000 0002',
    exp: '12/28',
    cvc: '123',
    nameOnCard: 'Synthetic Shopper',
  },
  // Card that triggers an insufficient funds decline
  INSUFFICIENT_FUNDS_CARD: {
    number: '4000 0000 0000 9995',
    exp: '12/28',
    cvc: '123',
    nameOnCard: 'Synthetic Shopper',
  },
};
