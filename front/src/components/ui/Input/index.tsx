import { InputHTMLAttributes, forwardRef } from 'react';
import { StyledInput } from './styles';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {}

export const Input = forwardRef<HTMLInputElement, InputProps>((props, ref) => {
  return <StyledInput ref={ref} {...props} />;
});

Input.displayName = 'Input';
