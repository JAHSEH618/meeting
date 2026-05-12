let counter = 0;
export function generateIdempotencyKey(prefix) {
    counter += 1;
    const timestamp = Date.now().toString(36);
    const random = Math.random().toString(36).slice(2, 8);
    return `${prefix}_${timestamp}_${random}_${counter}`;
}
