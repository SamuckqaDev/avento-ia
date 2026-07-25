import { InputHTMLAttributes, forwardRef } from 'react';
import { StyledDatePicker } from './styles';

export interface DatePickerProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  type?: 'date' | 'time' | 'datetime-local';
}

export const DatePicker = forwardRef<HTMLInputElement, DatePickerProps>(({ type = 'date', ...props }, ref) => {
  return <StyledDatePicker ref={ref} type={type} {...props} />;
});

DatePicker.displayName = 'DatePicker';
