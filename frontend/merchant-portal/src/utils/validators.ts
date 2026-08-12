import { z } from 'zod';

export const loginSchema = z.object({
  email: z.string().email('Invalid email address'),
  password: z.string().min(8, 'Password must be at least 8 characters'),
});

export const registerSchema = z.object({
  email: z.string().email('Invalid email address'),
  password: z
    .string()
    .min(8, 'Password must be at least 8 characters')
    .regex(/[A-Z]/, 'Must contain at least one uppercase letter')
    .regex(/[a-z]/, 'Must contain at least one lowercase letter')
    .regex(/[0-9]/, 'Must contain at least one number')
    .regex(/[^A-Za-z0-9]/, 'Must contain at least one special character'),
  firstName: z.string().min(1, 'First name is required'),
  lastName: z.string().min(1, 'Last name is required'),
  businessName: z.string().min(2, 'Business name is required'),
  businessType: z.string().min(1, 'Business type is required'),
});

export const webhookSchema = z.object({
  url: z.string().url('Must be a valid URL').startsWith('https', 'URL must use HTTPS'),
  events: z.array(z.string()).min(1, 'Select at least one event'),
});

export const apiKeySchema = z.object({
  name: z.string().min(1, 'Name is required').max(50, 'Name too long'),
  mode: z.enum(['TEST', 'LIVE']),
  permissions: z.array(z.string()).min(1, 'Select at least one permission'),
  expiresInDays: z.number().min(1).max(365).optional(),
});

export const refundSchema = z.object({
  amount: z.number().positive('Amount must be positive'),
  reason: z.string().min(5, 'Reason must be at least 5 characters'),
});

export const profileSchema = z.object({
  businessName: z.string().min(2, 'Business name is required'),
  email: z.string().email('Invalid email'),
  phone: z.string().min(10, 'Invalid phone number'),
  website: z.string().url('Invalid URL').optional().or(z.literal('')),
});

export type LoginFormData = z.infer<typeof loginSchema>;
export type RegisterFormData = z.infer<typeof registerSchema>;
export type WebhookFormData = z.infer<typeof webhookSchema>;
export type ApiKeyFormData = z.infer<typeof apiKeySchema>;
export type RefundFormData = z.infer<typeof refundSchema>;
export type ProfileFormData = z.infer<typeof profileSchema>;
