import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ModelSelector } from './ModelSelector';

describe('ModelSelector', () => {
  it('loads Junie OpenAI-compatible models and selects the configured default', async () => {
    const onChange = vi.fn();
    const configGet = vi.fn().mockResolvedValue({
      openaiCompatibleBaseUrl: 'https://openai-compatible.example.com',
      defaultModel: 'example-primary-model',
      fasterModel: 'example-fast-model',
    });
    const junieModelsCreate = vi.fn().mockResolvedValue({
      models: ['example-primary-model', 'example-fast-model', 'provider-extra-model'],
    });
    const client = {
      service: vi.fn((name: string) => {
        if (name === 'config') return { get: configGet };
        if (name === 'config/junie-models') return { create: junieModelsCreate };
        throw new Error(`Unexpected service: ${name}`);
      }),
    };

    render(
      <ModelSelector
        agentic_tool="junie"
        client={client as never}
        value={undefined}
        onChange={onChange}
      />
    );

    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith({
        mode: 'exact',
        model: 'example-primary-model',
      });
    });
    expect(configGet).toHaveBeenCalledWith('junie');
    expect(junieModelsCreate).toHaveBeenCalledWith({
      openaiCompatibleBaseUrl: 'https://openai-compatible.example.com',
    });

    fireEvent.mouseDown(screen.getByRole('combobox'));

    await waitFor(() => {
      expect(screen.getByText('provider-extra-model')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('provider-extra-model'));

    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith({
        mode: 'exact',
        model: 'provider-extra-model',
      });
    });
  });
});
