export type ApiCall = <T>(path: string, options?: RequestInit) => Promise<T>;
