const PROVIDERS = ['gemini', 'openai', 'anthropic', 'groq', 'perplexity'];

const KEY_ENV = Object.freeze({
  gemini: 'GEMINI_API_KEY',
  openai: 'OPENAI_API_KEY',
  anthropic: 'ANTHROPIC_API_KEY',
  groq: 'GROQ_API_KEY',
  perplexity: 'PERPLEXITY_API_KEY'
});

export function resolveProviderOrder({ requested = 'gemini', env = process.env } = {}) {
  if (requested !== 'auto') return PROVIDERS.includes(requested) ? [requested] : [];
  const configured = PROVIDERS.filter(name => Boolean(String(env[KEY_ENV[name]] || '').trim()));
  const preferred = String(env.AI_DEFAULT_PROVIDER || '').trim().toLowerCase();
  const custom = String(env.AI_PROVIDER_ORDER || '').split(',').map(x => x.trim().toLowerCase());
  const preference = [...(PROVIDERS.includes(preferred) ? [preferred] : []), ...custom, ...PROVIDERS];
  const ordered = [...new Set(preference)].filter(name => configured.includes(name));
  return ordered;
}

export function configuredProviderMap(env = process.env) {
  return Object.fromEntries(PROVIDERS.map(name => [name, Boolean(String(env[KEY_ENV[name]] || '').trim())]));
}

export const supportedProviders = Object.freeze([...PROVIDERS]);
