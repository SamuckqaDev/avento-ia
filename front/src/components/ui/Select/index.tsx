import { SelectHTMLAttributes, forwardRef } from 'react';
import { StyledSelect } from './styles';

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {}

export const Select = forwardRef<HTMLSelectElement, SelectProps>((props, ref) => {
  return <StyledSelect ref={ref} {...props} />;
});

Select.displayName = 'Select';
