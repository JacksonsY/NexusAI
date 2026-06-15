declare module 'markdown-it' {
  export interface MarkdownItOptions {
    html?: boolean;
    linkify?: boolean;
    typographer?: boolean;
    breaks?: boolean;
  }

  interface MarkdownIt {
    render(src: string): string;
    renderInline(src: string): string;
    use(plugin: (...args: unknown[]) => void, ...options: unknown[]): this;
  }

  /** markdown-it 默认导出既可 `new MarkdownIt()` 也可函数调用 `markdownit()` */
  interface MarkdownItFactory {
    new (options?: MarkdownItOptions): MarkdownIt;
    (options?: MarkdownItOptions): MarkdownIt;
  }

  const MarkdownIt: MarkdownItFactory;
  export default MarkdownIt;
}
