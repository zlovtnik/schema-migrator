const APP_TITLE = "Bedrock";

export const formatDocumentTitle = (title?: string | null): string => {
  const normalized = title?.trim();
  return normalized ? `${APP_TITLE} - ${normalized}` : APP_TITLE;
};
