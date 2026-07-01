import { Helmet } from "react-helmet-async";
import { formatDocumentTitle } from "./titleFormatting";

export const DocumentTitle = ({ title }: { title?: string | null | undefined }) => (
  <Helmet>
    <title>{formatDocumentTitle(title)}</title>
  </Helmet>
);
