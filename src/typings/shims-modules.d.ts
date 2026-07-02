declare module 'xlsx' {
  export function read(data: any, opts?: any): any;
  export function writeFile(workbook: any, filename: string, opts?: any): void;
  export const utils: any;
}
