import { useEffect, useState } from "react";
import { CopyIcon } from "@phosphor-icons/react/dist/csr/Copy";
import { TerminalIcon } from "@phosphor-icons/react/dist/csr/Terminal";
import { Icon } from "./ui/Icon";
import type { HighlighterCore } from "shiki/core";

interface SqlPreviewPaneProps {
  code?: string | null | undefined;
  title?: string;
}

export const SqlPreviewPane = ({ code, title = "SQL preview" }: SqlPreviewPaneProps) => {
  const [html, setHtml] = useState<string | null>(null);
  const text = code?.trim() || "";

  useEffect(() => {
    let active = true;
    if (!text) {
      setHtml(null);
      return () => {
        active = false;
      };
    }

    setHtml(null);

    loadSqlHighlighter()
      .then((highlighter) => highlighter.codeToHtml(text, { lang: "sql", theme: "github-dark" }))
      .then((value) => {
        if (active) {
          setHtml(value);
        }
      })
      .catch(() => {
        if (active) {
          setHtml(null);
        }
      });

    return () => {
      active = false;
    };
  }, [text]);

  const copy = () => {
    if (text) {
      void navigator.clipboard.writeText(text);
    }
  };

  return (
    <section className="sql-preview" aria-label={title}>
      <div className="sql-preview__toolbar">
        <span>
          <Icon source={TerminalIcon} size={16} />
          {title}
        </span>
        <button className="button button--ghost button--small" type="button" onClick={copy} disabled={!text}>
          <Icon source={CopyIcon} size={16} />
          Copy
        </button>
      </div>
      {text ? (
        html ? (
          <div className="sql-preview__code" dangerouslySetInnerHTML={{ __html: html }} />
        ) : (
          <pre className="sql-preview__fallback">
            <code>{text}</code>
          </pre>
        )
      ) : (
        <div className="empty-state">No SQL definition is available for this object.</div>
      )}
    </section>
  );
};

let sqlHighlighter: Promise<HighlighterCore> | undefined;

const loadSqlHighlighter = (): Promise<HighlighterCore> => {
  if (!sqlHighlighter) {
    sqlHighlighter = Promise.all([
      import("shiki/core"),
      import("shiki/engine/javascript"),
      import("shiki/langs/sql.mjs"),
      import("shiki/themes/github-dark.mjs")
    ]).then(([core, engine, sql, githubDark]) =>
      core.createHighlighterCore({
        themes: [githubDark.default],
        langs: [sql.default],
        engine: engine.createJavaScriptRegexEngine()
      })
    );
  }
  return sqlHighlighter;
};
