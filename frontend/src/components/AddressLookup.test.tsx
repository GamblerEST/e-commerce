import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import AddressLookup from './AddressLookup';
import { apiFetch } from '../api/client';
import type { AddressSuggestion } from '../api/types';

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  apiFetch: vi.fn(),
}));

const mockApiFetch = vi.mocked(apiFetch);

const PIKK_1: AddressSuggestion = {
  adsOid: 'EE00658009',
  label: 'Pikk tn 1, Kesklinna linnaosa, Tallinn, Harju maakond',
  line1: 'Pikk tn 1',
  city: 'Tallinn',
  postalCode: '10123',
  country: 'EE',
  apartment: '',
  apartmentOptions: [],
};

const MUSTAMAE_5: AddressSuggestion = {
  adsOid: 'ME02092715',
  label: 'Mustamäe tee 5, Kristiine linnaosa, Tallinn, Harju maakond',
  line1: 'Mustamäe tee 5',
  city: 'Tallinn',
  postalCode: '10616',
  country: 'EE',
  apartment: '',
  apartmentOptions: ['5', '12', '34'],
};

describe('AddressLookup', () => {
  it('suggests register addresses and hands the picked one to the form', async () => {
    mockApiFetch.mockResolvedValue([PIKK_1]);
    const onSelect = vi.fn();
    render(<AddressLookup onSelect={onSelect} />);

    await userEvent.type(screen.getByRole('combobox', { name: /find your address/i }), 'Pikk 1');
    await userEvent.click(await screen.findByRole('option', { name: PIKK_1.label }));

    expect(onSelect).toHaveBeenCalledWith(PIKK_1);
    expect(mockApiFetch).toHaveBeenCalledWith('/addresses/search?q=Pikk%201', expect.anything());
  });

  // A search box inside the checkout form: Enter there must pick or do nothing,
  // never place the order.
  it('does not submit the surrounding form when Enter is pressed', async () => {
    mockApiFetch.mockResolvedValue([PIKK_1]);
    const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <AddressLookup onSelect={vi.fn()} />
      </form>,
    );

    await userEvent.type(screen.getByRole('combobox', { name: /find your address/i }), 'Pikk{Enter}');

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('tells the customer to type the address in when search is unavailable', async () => {
    mockApiFetch.mockRejectedValue(new Error('Service Unavailable'));
    render(<AddressLookup onSelect={vi.fn()} />);

    await userEvent.type(screen.getByRole('combobox', { name: /find your address/i }), 'Pikk 1');

    expect(await screen.findByText(/not available right now/i)).toBeInTheDocument();
  });

  // A resolved apartment (the register matched a "-12" typed after the house
  // number) needs no second step - the whole point of typing it was to skip one.
  it('hands a search that already resolved an apartment straight to the form', async () => {
    mockApiFetch.mockResolvedValue([{ ...MUSTAMAE_5, apartment: '12' }]);
    const onSelect = vi.fn();
    render(<AddressLookup onSelect={onSelect} />);

    await userEvent.type(screen.getByRole('combobox', { name: /find your address/i }), 'Mustamäe tee 5-12');
    await userEvent.click(await screen.findByRole('option', { name: /Mustamäe tee 5/ }));

    expect(onSelect).toHaveBeenCalledWith({ ...MUSTAMAE_5, apartment: '12' });
  });

  describe('a building with registered flats', () => {
    async function openApartmentPicker() {
      mockApiFetch.mockResolvedValue([MUSTAMAE_5]);
      const onSelect = vi.fn();
      render(<AddressLookup onSelect={onSelect} />);

      await userEvent.type(screen.getByRole('combobox', { name: /find your address/i }), 'Mustamäe tee 5');
      await userEvent.click(await screen.findByRole('option', { name: /Mustamäe tee 5/ }));

      return onSelect;
    }

    it('offers its flats instead of finishing on the first pick', async () => {
      const onSelect = await openApartmentPicker();

      expect(await screen.findByText(/which apartment/i)).toBeInTheDocument();
      expect(screen.getByRole('option', { name: 'Apartment 12' })).toBeInTheDocument();
      expect(onSelect).not.toHaveBeenCalled();
    });

    it('finishes with the chosen apartment', async () => {
      const onSelect = await openApartmentPicker();

      await userEvent.click(await screen.findByRole('option', { name: 'Apartment 12' }));

      expect(onSelect).toHaveBeenCalledWith({ ...MUSTAMAE_5, apartment: '12' });
    });

    it('finishes with no apartment when the customer picks the building alone', async () => {
      const onSelect = await openApartmentPicker();

      await userEvent.click(await screen.findByRole('option', { name: /just the building/i }));

      expect(onSelect).toHaveBeenCalledWith({ ...MUSTAMAE_5, apartment: '' });
    });

    it('goes back to the building list without losing the search', async () => {
      const onSelect = await openApartmentPicker();

      await userEvent.click(screen.getByRole('button', { name: /back/i }));

      expect(await screen.findByRole('option', { name: /Mustamäe tee 5/ })).toBeInTheDocument();
      expect(onSelect).not.toHaveBeenCalled();
    });
  });
});
