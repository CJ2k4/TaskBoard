/**
 * The full-page message used for every "there is nothing to show yet, or ever" state —
 * bootstrapping the session, redirecting, redeeming an invite, a deleted board.
 *
 * Four routes previously each had their own near-identical `CenteredNote`; this is the one
 * they now share, so a terminal state looks the same wherever the user hits it.
 *
 * `busy` swaps the leading glyph for a spinner: use it when something is still in flight
 * ("Joining board…") and leave it off for a settled outcome ("This link is no longer valid").
 */
export function PageNote({
  children,
  busy = false,
}: {
  children: React.ReactNode;
  busy?: boolean;
}) {
  return (
    <main className="animate-page-in relative flex flex-1 items-center justify-center overflow-hidden bg-canvas p-8">
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="animate-float-slow absolute -left-32 -top-32 h-[24rem] w-[24rem] rounded-full bg-brand-200/20 blur-3xl" />
      </div>

      <div className="animate-spring-in relative max-w-sm rounded-2xl border border-line bg-paper px-8 py-7 text-center shadow-[var(--shadow-md)]">
        {busy && (
          <span
            aria-hidden
            className="animate-spin-slow mx-auto mb-4 block h-6 w-6 rounded-full border-2 border-brand-200 border-t-brand-600"
          />
        )}
        {/* Links and buttons inside the message get the brand treatment without every caller
            having to remember the classes. */}
        <p className="text-sm leading-relaxed text-zinc-600 [&_a]:font-semibold [&_a]:text-brand-600 [&_a]:underline [&_a]:underline-offset-2 [&_a:hover]:text-brand-700 [&_button]:font-semibold [&_button]:text-brand-600 [&_button]:underline [&_button]:underline-offset-2 [&_button:hover]:text-brand-700">
          {children}
        </p>
      </div>
    </main>
  );
}
