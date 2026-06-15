export type ApiCall = <T>(path: string, options?: RequestInit) => Promise<T>;
export type ApiDownload = (path: string) => Promise<Blob>;
