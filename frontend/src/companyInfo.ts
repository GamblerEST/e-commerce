// Single source of truth for the seller-identification details that legally
// have to appear in multiple places (footer, Contacts page, Sales
// Conditions) — keeping them in one file means they can't drift out of
// sync with each other.
export const COMPANY = {
  name: 'Estonian Shop OÜ',
  registryCode: '16123456',
  address: 'Pikk tn 1, 10123 Tallinn, Estonia',
  email: 'hello@shop.ee',
  phone: '+372 5555 5555',
};

/**
 * About-page content. Lives here rather than inline in the page for the same
 * reason as the details above: the mission line and team also appear in
 * marketing copy and must not drift.
 */
export const ABOUT = {
  mission:
    'Sell a small, carefully chosen range of everyday things at an honest price, ' +
    'with the tax included in the number on the label and no dark patterns at checkout.',
  founded: 2024,
  story:
    'Estonian Shop began as a single storeroom in Tallinn old town, stocking the ' +
    'kind of durable, unfashionable goods that are surprisingly hard to buy well. ' +
    'We still buy in small quantities from makers we have met, and we still ship ' +
    'every order ourselves.',
  values: [
    {
      title: 'The price is the price',
      detail:
        'Every figure you see includes VAT. Shipping is shown before you are asked for a card, never after.',
    },
    {
      title: 'Fewer, better things',
      detail:
        'We would rather carry one kettle that lasts a decade than nine that do not.',
    },
    {
      title: 'Your data stays yours',
      detail:
        'We collect what an order needs and nothing else, and we never sell it on.',
    },
  ],
  team: [
    { name: 'Mari Tamm', role: 'Founder & buyer' },
    { name: 'Jaan Kask', role: 'Operations & fulfilment' },
    { name: 'Liis Oja', role: 'Customer support' },
  ],
  social: [
    { label: 'Facebook', href: 'https://www.facebook.com/' },
    { label: 'Instagram', href: 'https://www.instagram.com/' },
    { label: 'LinkedIn', href: 'https://www.linkedin.com/' },
  ],
};
