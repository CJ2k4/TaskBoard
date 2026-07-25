import Link from "next/link";

// Public landing page. A Server Component — static marketing content plus links into the auth
// flow (sign-in is Google-only, so both CTAs route to /login).

const FEATURES = [
  {
    label: "Live sync",
    title: "Everyone, same board",
    body: "Moves, edits and new cards stream to every client over WebSockets — no refresh, no stale board.",
  },
  {
    label: "Conflicts",
    title: "Collisions, handled",
    body: "When two people touch the same card, TaskBoard shows both versions and lets you pick the winner.",
  },
  {
    label: "Activity",
    title: "Nothing gets lost",
    body: "A running activity log and presence show who did what, and who is looking right now.",
  },
];

export default function Home() {
  return (
    <main className="min-h-screen w-full bg-[#F1EFEA]">
      <div className="mx-auto flex min-h-screen w-full max-w-6xl flex-col px-6">
        {/* Nav */}
        <nav className="flex items-center justify-between py-6">
          <div className="flex items-center gap-2.5">
            <span className="flex h-9 w-9 items-center justify-center rounded-[0.6rem] bg-indigo-600 text-sm font-bold text-white">
              TB
            </span>
            <span className="text-lg font-bold tracking-tight text-zinc-900">
              TaskBoard
            </span>
          </div>
          <Link
            href="/login"
            className="rounded-xl bg-zinc-900 px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-zinc-800"
          >
            Sign in
          </Link>
        </nav>

        {/* Hero */}
        <section className="flex flex-1 flex-col items-center justify-center py-16 text-center sm:py-24">
          <span className="inline-flex items-center gap-2 rounded-full border border-zinc-300/70 bg-white/50 px-4 py-1.5 font-mono text-xs text-zinc-600">
            <span className="h-1.5 w-1.5 rounded-full bg-indigo-500" aria-hidden />
            Real-time · multiplayer boards
          </span>

          <h1 className="mt-8 max-w-4xl text-5xl font-bold leading-[1.03] tracking-tight text-zinc-900 sm:text-6xl lg:text-7xl">
            The board your whole team edits at once
          </h1>

          <p className="mt-6 max-w-2xl text-lg leading-relaxed text-zinc-500 sm:text-xl">
            Drag a card and everyone sees it move — live. TaskBoard keeps boards, columns and
            cards in sync across every client, and sorts out conflicts when edits collide.
          </p>

          <Link
            href="/login"
            className="mt-10 rounded-xl bg-indigo-600 px-7 py-3.5 text-base font-semibold text-white transition hover:bg-indigo-500"
          >
            Get started
          </Link>
        </section>

        {/* Feature cards */}
        <section className="grid grid-cols-1 gap-5 pb-16 md:grid-cols-3">
          {FEATURES.map((f) => (
            <div
              key={f.label}
              className="rounded-2xl border border-zinc-200/70 bg-[#FBFAF7] p-7 text-left"
            >
              <p className="font-mono text-xs font-semibold uppercase tracking-wider text-indigo-600">
                {f.label}
              </p>
              <h3 className="mt-3 text-lg font-bold text-zinc-900">{f.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-zinc-500">{f.body}</p>
            </div>
          ))}
        </section>
      </div>
    </main>
  );
}
