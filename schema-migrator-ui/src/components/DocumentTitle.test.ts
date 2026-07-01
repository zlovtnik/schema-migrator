import { describe, expect, it } from "vitest";
import { formatDocumentTitle } from "./titleFormatting";

describe("formatDocumentTitle", () => {
  it("formats page titles with the Bedrock prefix", () => {
    expect(formatDocumentTitle("Settings")).toBe("Bedrock - Settings");
  });

  it("falls back to the app title without a page title", () => {
    expect(formatDocumentTitle("")).toBe("Bedrock");
  });
});
