import configuration from '../../catalogue/taxonomy.json';

const normalize = (value: string) => value.normalize('NFKC').toLowerCase().trim();
const escape = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
function pattern(term: string): RegExp {
  const text = normalize(term);
  if (/^[\x00-\x7f]+$/.test(text)) {
    const words = text.match(/[a-z0-9]+/g) ?? [];
    return new RegExp(`(?:^|[^a-z0-9])${words.map(escape).join('[\\s\\W_]*')}(?![a-z0-9])`);
  }
  return new RegExp(escape(text));
}
export const taxonomy = configuration.categories.map(({ id, label }) => ({ id, label }));
const genreRules = configuration.match_priority.map(id => ({
  id, patterns: configuration.categories.find(category => category.id === id)!.terms.map(pattern),
}));
const filterRules = configuration.filters.map(filter => ({ id: filter.id, patterns: filter.terms.map(pattern) }));
export function classify(label: string, group = '', sourceId = ''): { id: string; label: string; filterIds: string[] } {
  const texts = [normalize(label), normalize(group)];
  const overrides = configuration.source_overrides as Record<string, Record<string, string>>;
  let id: string | undefined = overrides[sourceId]?.[texts[0]];
  for (const text of texts) {
    if (id) break;
    id = genreRules.find(rule => rule.patterns.some(regex => regex.test(text)))?.id;
  }
  const selected = taxonomy.find(category => category.id === id) ?? taxonomy.find(category => category.id === configuration.fallback)!;
  return { ...selected, filterIds: filterRules.filter(rule => rule.patterns.some(regex => texts.some(text => regex.test(text)))).map(rule => rule.id) };
}
