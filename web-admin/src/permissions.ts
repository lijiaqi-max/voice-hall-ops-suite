export function hasPermission(granted: readonly string[], required: string): boolean {
  return granted.includes("*") ||
    granted.includes(required) ||
    (required.endsWith(".read") && granted.includes(`${required.slice(0, -5)}.write`));
}

export function hasAnyPermission(
  granted: readonly string[],
  required: readonly string[],
): boolean {
  return required.length === 0 || required.some((permission) => hasPermission(granted, permission));
}
