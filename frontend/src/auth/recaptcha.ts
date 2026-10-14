// Loads Google's reCAPTCHA v2 (checkbox) script exactly once, however many
// times components using it mount/remount. Explicit render mode (not the
// automatic-scan mode) because React controls when the container div
// actually exists in the DOM.
let loadPromise: Promise<void> | null = null;

export function loadRecaptchaScript(): Promise<void> {
  if (loadPromise) return loadPromise;

  loadPromise = new Promise((resolve) => {
    if (window.grecaptcha) {
      resolve();
      return;
    }
    const callbackName = '__onRecaptchaLoad';
    (window as unknown as Record<string, () => void>)[callbackName] = () => resolve();

    const script = document.createElement('script');
    script.src = `https://www.google.com/recaptcha/api.js?render=explicit&onload=${callbackName}`;
    script.async = true;
    script.defer = true;
    document.head.appendChild(script);
  });

  return loadPromise;
}
