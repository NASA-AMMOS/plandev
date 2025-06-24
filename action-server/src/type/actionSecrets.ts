export class ActionSecrets {
  private static actionSecrets: Record<number, any> = {};

  static addActionSecret(actionRunId: number, secret: any): void {
    this.actionSecrets[actionRunId] = secret;
  }

  static getActionSecret(actionRunId: number): any | undefined {
    return this.actionSecrets[actionRunId] ?? undefined;
  }

  static removeActionSecret(actionRunId: number): void {
    delete this.actionSecrets[actionRunId];
  }
}
