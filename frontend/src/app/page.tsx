import Link from "next/link";

// Public landing page. Still a Server Component — every bit of motion here is pure CSS
// (entrance animations, ambient drift, hover transitions), so nothing needs to ship JS.

const FEATURES = [
  {
    label: "Live sync",
    title: "Everyone, same board",
    body: "Moves, edits and new cards stream to every client over WebSockets — no refresh, no stale board.",
    icon: "◎",
  },
  {
    label: "Conflicts",
    title: "Collisions, handled",
    body: "When two people touch the same card, TaskBoard shows both versions and lets you pick the winner.",
    icon: "⇄",
  },
  {
    label: "Activity",
    title: "Nothing gets lost",
    body: "A running activity log and presence show who did what, and who is looking right now.",
    icon: "◷",
  },
];

/** The fake board rendered under the hero — a still life of the real thing. */
const PREVIEW = [
  {
    title: "Backlog",
    accent: "bg-zinc-400",
    cards: [
      { text: "Rank re-balance on exhaustion", label: "Backend" },
      { text: "Presence avatars in header", label: null },
    ],
  },
  {
    title: "In progress",
    accent: "bg-brand-500",
    cards: [
      { text: "Drag columns by the grip", label: "Frontend" },
      { text: "Invite links, rotatable", label: null },
      { text: "Activity drawer", label: "UX" },
    ],
  },
  {
    title: "Done",
    accent: "bg-emerald-500",
    cards: [{ text: "STOMP over WebSocket", label: "Realtime" }],
  },
];

export default function Home() {
  return (
    <main className="relative min-h-screen w-full overflow-hidden bg-canvas">
      {/* Ambient background: two slow-drifting colour fields plus a faint grid. They sit
          behind everything and never intercept a pointer. */}
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="animate-float-slow absolute -left-40 -top-40 h-[34rem] w-[34rem] rounded-full bg-brand-300/30 blur-3xl" />
        <div
          className="animate-float-slow absolute -right-32 top-32 h-[28rem] w-[28rem] rounded-full bg-violet-300/25 blur-3xl"
          style={{ animationDelay: "-6s" }}
        />
        <div
          className="animate-float-slow absolute bottom-0 left-1/3 h-[24rem] w-[24rem] rounded-full bg-amber-200/30 blur-3xl"
          style={{ animationDelay: "-10s" }}
        />
        <div
          className="absolute inset-0 opacity-[0.35]"
          style={{
            backgroundImage:
              "linear-gradient(rgba(29,28,24,0.045) 1px, transparent 1px), linear-gradient(90deg, rgba(29,28,24,0.045) 1px, transparent 1px)",
            backgroundSize: "56px 56px",
            maskImage: "radial-gradient(ellipse at 50% 0%, black 20%, transparent 72%)",
            WebkitMaskImage: "radial-gradient(ellipse at 50% 0%, black 20%, transparent 72%)",
          }}
        />
      </div>

      <div className="relative mx-auto flex min-h-screen w-full max-w-6xl flex-col px-6">
        {/* Nav */}
        <nav className="animate-fade-in flex items-center justify-between py-6">
          <div className="group flex items-center gap-2.5">
            <span className="flex h-9 w-9 items-center justify-center rounded-[0.7rem] bg-gradient-to-br from-brand-500 to-violet-600 text-sm font-bold text-white shadow-[var(--shadow-brand)] transition-transform duration-300 group-hover:rotate-6 group-hover:scale-110">
              TB
            </span>
            <span className="text-lg font-bold tracking-tight text-zinc-900">TaskBoard</span>
          </div>
          <Link
            href="/login"
            className="press relative overflow-hidden rounded-xl bg-zinc-900 px-5 py-2.5 text-sm font-semibold text-white hover:bg-zinc-800 sheen"
          >
            Sign in
          </Link>
        </nav>

        {/* Hero */}
        <section className="flex flex-col items-center py-16 text-center sm:py-24">
          <span
            className="animate-rise-in inline-flex items-center gap-2 rounded-full border border-line-strong bg-paper/70 px-4 py-1.5 font-mono text-xs text-zinc-600 shadow-[var(--shadow-sm)] backdrop-blur"
            style={{ animationDelay: "40ms" }}
          >
            <span className="relative flex h-1.5 w-1.5">
              <span className="absolute inline-flex h-full w-full rounded-full bg-brand-500 opacity-75 [animation:pulse-ring_2.4s_var(--ease-out-soft)_infinite]" />
              <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-brand-600" />
            </span>
            Real-time · multiplayer boards
          </span>

          <h1
            className="animate-rise-in mt-8 max-w-4xl text-5xl font-bold leading-[1.03] tracking-tight sm:text-6xl lg:text-7xl"
            style={{ animationDelay: "120ms" }}
          >
            <span className="text-gradient">The board your whole team edits at once</span>
          </h1>

          <p
            className="animate-rise-in mt-6 max-w-2xl text-lg leading-relaxed text-zinc-500 sm:text-xl"
            style={{ animationDelay: "200ms" }}
          >
            Drag a card and everyone sees it move — live. TaskBoard keeps boards, columns and
            cards in sync across every client, and sorts out conflicts when edits collide.
          </p>

          <div
            className="animate-rise-in mt-10 flex flex-wrap items-center justify-center gap-3"
            style={{ animationDelay: "280ms" }}
          >
            <Link
              href="/login"
              className="press sheen relative overflow-hidden rounded-xl bg-gradient-to-br from-brand-500 to-violet-600 px-7 py-3.5 text-base font-semibold text-white shadow-[var(--shadow-brand)] hover:shadow-[0_12px_32px_-8px_rgba(79,70,229,0.6)]"
            >
              Get started
            </Link>
            <Link
              href="#features"
              className="press rounded-xl border border-line-strong bg-paper/60 px-6 py-3.5 text-base font-semibold text-zinc-700 backdrop-blur hover:bg-paper"
            >
              See how it works
            </Link>
          </div>
        </section>

        <BoardPreview />

        {/* Feature cards */}
        <section id="features" className="stagger grid grid-cols-1 gap-5 py-20 md:grid-cols-3">
          {FEATURES.map((f) => (
            <div
              key={f.label}
              className="lift group relative overflow-hidden rounded-2xl border border-line bg-paper p-7 text-left shadow-[var(--shadow-sm)] hover:border-brand-200"
            >
              {/* A brand wash that fades up from the bottom on hover. */}
              <div
                aria-hidden
                className="pointer-events-none absolute inset-x-0 bottom-0 h-32 bg-gradient-to-t from-brand-50 to-transparent opacity-0 transition-opacity duration-500 group-hover:opacity-100"
              />
              <div className="relative">
                <span className="mb-4 flex h-10 w-10 items-center justify-center rounded-xl border border-brand-100 bg-brand-50 text-lg text-brand-600 transition-transform duration-300 group-hover:-rotate-6 group-hover:scale-110">
                  {f.icon}
                </span>
                <p className="font-mono text-xs font-semibold uppercase tracking-wider text-brand-600">
                  {f.label}
                </p>
                <h3 className="mt-2 text-lg font-bold text-zinc-900">{f.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-zinc-500">{f.body}</p>
              </div>
            </div>
          ))}
        </section>
      </div>
    </main>
  );
}

/**
 * A non-interactive mock of a board, tilted slightly and floating under the hero. It exists to
 * show — rather than describe — what the product is before anyone signs in. Cards stagger in,
 * and the whole slab straightens out when you hover it.
 */
function BoardPreview() {
  return (
    <section
      className="animate-rise-in relative mx-auto w-full max-w-4xl px-2 pb-8"
      style={{ animationDelay: "360ms" }}
      aria-hidden
    >
      <div className="group [perspective:1800px]">
        <div className="rounded-2xl border border-line bg-paper/80 p-4 shadow-[var(--shadow-xl)] backdrop-blur-sm transition-transform duration-700 ease-[cubic-bezier(0.22,1,0.36,1)] [transform:rotateX(14deg)_scale(0.96)] group-hover:[transform:rotateX(2deg)_scale(1)]">
          {/* Window chrome */}
          <div className="mb-4 flex items-center gap-2 border-b border-line pb-3">
            <span className="h-2.5 w-2.5 rounded-full bg-red-400/70" />
            <span className="h-2.5 w-2.5 rounded-full bg-amber-400/70" />
            <span className="h-2.5 w-2.5 rounded-full bg-emerald-400/70" />
            <span className="ml-3 font-mono text-[11px] text-zinc-400">taskboard / sprint 14</span>
            <span className="ml-auto inline-flex items-center gap-1.5 font-mono text-[11px] text-emerald-600">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
              Live
            </span>
          </div>

          <div className="grid grid-cols-3 gap-3">
            {PREVIEW.map((col, ci) => (
              <div key={col.title} className="rounded-xl bg-sunken/70 p-2.5">
                <div className="mb-2 flex items-center gap-1.5 px-1">
                  <span className={`h-1.5 w-1.5 rounded-full ${col.accent}`} />
                  <span className="text-[11px] font-semibold text-zinc-600">{col.title}</span>
                  <span className="ml-auto font-mono text-[10px] text-zinc-400">
                    {col.cards.length}
                  </span>
                </div>
                <div className="flex flex-col gap-2">
                  {col.cards.map((card, i) => (
                    <div
                      key={card.text}
                      className="animate-rise-in rounded-lg border border-line bg-paper px-2.5 py-2 shadow-[var(--shadow-sm)]"
                      style={{ animationDelay: `${480 + ci * 90 + i * 70}ms` }}
                    >
                      {card.label && (
                        <span className="mb-1 inline-block rounded bg-brand-50 px-1.5 py-0.5 font-mono text-[9px] font-semibold uppercase tracking-wide text-brand-600">
                          {card.label}
                        </span>
                      )}
                      <p className="text-[11px] leading-snug text-zinc-700">{card.text}</p>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
