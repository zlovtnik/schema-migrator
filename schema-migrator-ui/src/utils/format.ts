interface FormatOptionalDateOptions {
  emptyLabel?: string;
  invalidLabel?: string;
}

export const formatOptionalDate = (value?: string | null, options: FormatOptionalDateOptions = {}): string => {
  if (!value) {
    return options.emptyLabel ?? "-";
  }
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) {
    return options.invalidLabel ?? value;
  }
  return new Date(parsed).toLocaleString();
};
