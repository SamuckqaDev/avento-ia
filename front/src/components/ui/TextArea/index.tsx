import { TextareaHTMLAttributes, forwardRef } from 'react';
import { StyledTextArea } from './styles';

export interface TextAreaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {}

export const TextArea = forwardRef<HTMLTextAreaElement, TextAreaProps>((props, ref) => {
  return <StyledTextArea ref={ref} {...props} />;
});

TextArea.displayName = 'TextArea';
