import type { ComponentType } from "react";
import type { IconProps as PhosphorIconProps, IconWeight } from "@phosphor-icons/react/dist/lib/types";

export type IconSize = 16 | 20 | 24;
export type IconSource = ComponentType<PhosphorIconProps>;

interface IconProps {
  source: IconSource;
  size?: IconSize;
  label?: string;
  weight?: IconWeight;
  className?: string;
}

export const Icon = ({ source: Source, size = 16, label, weight = "regular", className }: IconProps) => (
  <Source
    aria-hidden={label ? undefined : "true"}
    aria-label={label}
    className={className}
    focusable="false"
    role={label ? "img" : undefined}
    size={size}
    weight={weight}
  />
);
