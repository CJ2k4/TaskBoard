"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import { useAuth } from "@/lib/auth-context";
import { BrandMark } from "@/components/brand-mark";

/**
 * Top navigation bar: app name on the left, profile menu on the right.
 *
 * Sticky and frosted, so content scrolling underneath stays faintly visible rather than
 * disappearing under an opaque slab.
 */
export function Navbar() {
  return (
    <nav className="glass sticky top-0 z-40 flex h-14 items-center justify-between border-b border-line px-6">
      <BrandMark />
      <ProfileMenu />
    </nav>
  );
}

/** Profile avatar that opens a dropdown with a log-out option. */
function ProfileMenu() {
  const { user, logout } = useAuth();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  const initial = user?.name?.trim().charAt(0).toUpperCase() || "?";

  // Close on outside click or Escape while the menu is open.
  useEffect(() => {
    if (!open) return;
    function onPointerDown(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  function handleLogout() {
    setOpen(false);
    logout();
    router.replace("/login");
  }

  return (
    <div ref={menuRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        title={user?.name ?? undefined}
        aria-haspopup="menu"
        aria-expanded={open}
        className={`press flex h-9 w-9 items-center justify-center overflow-hidden rounded-full border bg-gradient-to-br from-zinc-100 to-zinc-200 text-sm font-semibold text-zinc-700 ring-2 ring-transparent ring-offset-2 ring-offset-paper hover:ring-brand-200 ${
          open ? "border-brand-300 ring-brand-200" : "border-line-strong"
        }`}
      >
        {user?.imageUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={user.imageUrl} alt={user.name} className="h-full w-full object-cover" />
        ) : (
          initial
        )}
      </button>

      {open && (
        <div
          role="menu"
          className="animate-pop-in absolute right-0 top-12 z-50 w-52 origin-top-right overflow-hidden rounded-xl border border-line bg-paper py-1 shadow-[var(--shadow-lg)]"
        >
          {user && (
            <div className="border-b border-line px-3 py-2.5">
              <p className="truncate text-sm font-medium text-zinc-900">{user.name}</p>
              <p className="truncate text-xs text-zinc-500">{user.email}</p>
            </div>
          )}
          <button
            type="button"
            role="menuitem"
            onClick={handleLogout}
            className="group flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-zinc-700 transition-colors duration-150 hover:bg-red-50 hover:text-red-700"
          >
            <span className="transition-transform duration-200 group-hover:translate-x-0.5">→</span>
            Log out
          </button>
        </div>
      )}
    </div>
  );
}
