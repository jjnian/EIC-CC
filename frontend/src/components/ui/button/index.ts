import type { VariantProps } from "class-variance-authority"
import { cva } from "class-variance-authority"

export { default as Button } from "./Button.vue"

export const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium select-none transition-[transform,box-shadow,background-color,border-color,color] duration-150 ease-out disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-4 shrink-0 [&_svg]:shrink-0 outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive",
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground shadow-[0_2px_10px_-3px_var(--accent-glow)] hover:bg-[var(--accent-soft)] hover:-translate-y-px hover:shadow-[0_8px_20px_-4px_var(--accent-glow)] active:translate-y-0 active:shadow-[0_1px_4px_-2px_var(--accent-glow)]",
        destructive:
          "bg-destructive text-white shadow-[0_2px_10px_-3px_rgba(229,72,77,0.4)] hover:bg-destructive/90 hover:-translate-y-px hover:shadow-[0_8px_20px_-4px_rgba(229,72,77,0.4)] active:translate-y-0 focus-visible:ring-destructive/20 dark:focus-visible:ring-destructive/40",
        outline:
          "border border-[var(--glass-border)] bg-transparent hover:bg-accent hover:text-accent-foreground hover:border-[var(--glass-border-strong)]",
        secondary:
          "bg-secondary text-secondary-foreground shadow-xs hover:bg-[var(--bg-elev-hi)] hover:-translate-y-px hover:shadow-sm active:translate-y-0",
        ghost:
          "hover:bg-accent hover:text-accent-foreground dark:hover:bg-accent/50",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        "default": "h-9 px-4 py-2 has-[>svg]:px-3",
        "sm": "h-8 rounded-md gap-1.5 px-3 has-[>svg]:px-2.5",
        "lg": "h-10 rounded-md px-6 has-[>svg]:px-4",
        "icon": "size-9",
        "icon-sm": "size-8",
        "icon-lg": "size-10",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
)
export type ButtonVariants = VariantProps<typeof buttonVariants>
