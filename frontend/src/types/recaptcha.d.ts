interface Window {
  grecaptcha?: {
    render(container: HTMLElement, params: { sitekey: string }): number;
    getResponse(widgetId?: number): string;
    reset(widgetId?: number): void;
  };
}

interface ImportMetaEnv {
  readonly VITE_RECAPTCHA_SITE_KEY?: string;
  readonly VITE_STRIPE_PUBLISHABLE_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
