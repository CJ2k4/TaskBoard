/**
 * Shared avatar helpers: a monogram and a stable colour for a person. Used by the presence
 * stack, the card assignee, and anywhere else a small initial-avatar stands in for a user.
 */

/** Two-initial monogram from a display name, e.g. "Ada Lovelace" → "AL". */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** A stable-per-person hue (0–359) so an avatar keeps its colour across renders. */
export function hue(id: string): number {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) % 360;
  return h;
}

/** The background colour for a person's avatar, keyed off their id. */
export function avatarColor(id: string): string {
  return `hsl(${hue(id)} 55% 45%)`;
}
