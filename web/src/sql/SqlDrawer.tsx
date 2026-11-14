import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Play } from "lucide-react";
import { useLocation } from "react-router";
import { errorMessage } from "@/api/client";
import { fetchers } from "@/api/endpoints";
import { useSession } from "@/auth/session";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetTitle } from "@/components/ui/sheet";
import { cn } from "@/lib/utils";
import { useScreenQueries } from "./queryLog";

const KEYWORDS =
  /\b(SELECT|FROM|WHERE|JOIN|LEFT|LATERAL|CROSS|ON|AND|OR|NOT|EXISTS|GROUP BY|ORDER BY|LIMIT|OFFSET|WITH|AS|CASE|WHEN|THEN|ELSE|END|INSERT INTO|VALUES|RETURNING|UPDATE|SET|ALTER TABLE|ADD CONSTRAINT|CONSTRAINT|EXCLUDE USING|OVER|PARTITION BY|IS|NULL|IN|BETWEEN|DESC|ASC|DISTINCT|FILTER|CAST)\b/g;

/** Colours SQL by building React text nodes, so nothing from the server is ever injected as HTML. */
function Highlighted({ sql }: { sql: string }) {
  const lines = useMemo(() => sql.split("\n"), [sql]);
  return (
    <code>
      {lines.map((line, i) => {
        if (line.trimStart().startsWith("--")) {
          return (
            <span key={i} className="block text-bottle-soft italic">
              {line || " "}
            </span>
          );
        }
        const nodes: ReactNode[] = [];
        let last = 0;
        for (const match of line.matchAll(KEYWORDS)) {
          nodes.push(line.slice(last, match.index));
          nodes.push(
            <span key={match.index} className="font-semibold text-auto-yellow">
              {match[0]}
            </span>,
          );
          last = match.index + match[0].length;
        }
        nodes.push(line.slice(last));
        return (
          <span key={i} className="block">
            {nodes}
            {line === "" ? " " : null}
          </span>
        );
      })}
    </code>
  );
}

function QueryPanel({ queryKey }: { queryKey: string }) {
  const { role } = useSession();
  const info = useQuery({ queryKey: ["sql", queryKey], queryFn: () => fetchers.query(queryKey), staleTime: Infinity });
  const explain = useMutation({ mutationFn: () => fetchers.explain(queryKey) });

  if (info.isPending) return <p className="text-bottle-soft">Loading the query…</p>;
  if (info.isError) return <p role="alert">{errorMessage(info.error)}</p>;
  const q = info.data;

  return (
    <div className="grid min-w-0 gap-4">
      <p className="text-white/90">{q.description}</p>
      <ul className="flex flex-wrap gap-1.5" aria-label="Techniques used">
        {q.techniques.map((t) => (
          <li key={t} className="rounded-sm border border-bottle-soft/50 px-2 py-0.5 font-mono text-xs text-white">
            {t}
          </li>
        ))}
      </ul>
      <pre
        tabIndex={0}
        aria-label="SQL query"
        className="max-h-[45vh] overflow-auto bg-black/30 p-3 font-mono text-[0.78rem] leading-relaxed text-white"
      >
        <Highlighted sql={q.sql} />
      </pre>
      <p className="text-sm text-bottle-soft">
        <span className="font-semibold text-white">Benchmark: </span>
        {q.measuredMs != null
          ? `${q.measuredMs} ms, the median of 3 runs on a test database with 1 million bookings`
          : "not measured, because this statement changes data"}
      </p>

      {role === "admin" && q.explainable && (
        <div className="grid min-w-0 gap-2">
          <Button
            variant="auto"
            size="sm"
            className="justify-self-start"
            disabled={explain.isPending}
            onClick={() => explain.mutate()}
          >
            <Play aria-hidden /> {explain.isPending ? "Running…" : "Run EXPLAIN"}
          </Button>
          {explain.isError && <p role="alert">{errorMessage(explain.error)}</p>}
          {explain.data && (
            <pre
              tabIndex={0}
              aria-label="Query plan"
              className="max-h-[30vh] overflow-auto bg-black/30 p-3 font-mono text-[0.72rem] leading-relaxed text-white"
            >
              {explain.data.plan.join("\n")}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}

const SqlContext = createContext<{ open: (key?: string) => void; count: number } | null>(null);

export function useSqlDrawer() {
  const value = useContext(SqlContext);
  if (!value) throw new Error("useSqlDrawer must be used inside SqlDrawerProvider");
  return value;
}

export function SqlDrawerProvider({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const screenKeys = useScreenQueries(pathname);
  const [isOpen, setOpen] = useState(false);
  const [pinned, setPinned] = useState<string | undefined>();
  const [chosen, setChosen] = useState<string | undefined>();

  const keys = pinned && !screenKeys.includes(pinned) ? [pinned, ...screenKeys] : screenKeys;
  const active = chosen && keys.includes(chosen) ? chosen : (pinned ?? keys[0]);

  const value = useMemo(
    () => ({
      count: screenKeys.length,
      open: (key?: string) => {
        setPinned(key);
        setChosen(key);
        setOpen(true);
      },
    }),
    [screenKeys.length],
  );

  return (
    <SqlContext.Provider value={value}>
      {children}
      <Sheet open={isOpen} onOpenChange={setOpen}>
        <SheetContent className="on-dark w-full gap-0 border-none bg-bottle p-0 text-white sm:max-w-xl">
          <div className="border-b border-white/15 px-5 py-4 pr-12">
            <SheetTitle className="font-display text-2xl text-white">The SQL behind this screen</SheetTitle>
            <SheetDescription className="text-sm text-bottle-soft">
              The exact statements the API ran, read from the code that runs them.
            </SheetDescription>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
            {keys.length > 1 && (
              <div role="tablist" aria-label="Queries on this screen" className="mb-4 flex flex-wrap gap-1.5">
                {keys.map((key) => (
                  <button
                    key={key}
                    role="tab"
                    aria-selected={key === active}
                    onClick={() => setChosen(key)}
                    className={cn(
                      "rounded-sm px-2 py-1 font-mono text-xs",
                      key === active ? "bg-auto-yellow text-ink" : "bg-white/10 text-white hover:bg-white/20",
                    )}
                  >
                    {key}
                  </button>
                ))}
              </div>
            )}
            {active ? <QueryPanel key={active} queryKey={active} /> : <p>This screen hasn't run any queries yet.</p>}
          </div>
        </SheetContent>
      </Sheet>
    </SqlContext.Provider>
  );
}
