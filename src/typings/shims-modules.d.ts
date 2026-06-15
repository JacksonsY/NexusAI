declare module '@tanstack/vue-virtual' {
  export function useVirtualizer(options: any): any;
  export function useWindowVirtualizer(options: any): any;
}

declare module 'mammoth' {
  export function convertToHtml(input: any, options?: any): Promise<{ value: string; messages: any[] }>;
  export function extractRawText(input: any, options?: any): Promise<{ value: string; messages: any[] }>;
}

declare module 'xlsx' {
  export function read(data: any, opts?: any): any;
  export const utils: any;
}
