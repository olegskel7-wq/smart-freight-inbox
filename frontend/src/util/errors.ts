/** The message of a caught error — an ApiError carries the server's message — else the fallback. */
export const errorMessage = (e: unknown, fallback: string): string => (e instanceof Error ? e.message : fallback)
