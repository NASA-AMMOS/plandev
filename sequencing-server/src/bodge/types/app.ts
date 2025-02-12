import type { PermissibleQueriesMap, RolePermissionsMap } from './permissions';

export type UserId = string | null;

export type BaseUser = {
  id: UserId;
  token: string;
};

export type UserRole = string | 'aerie_admin';

export type User = BaseUser & {
  activeRole: UserRole;
  allowedRoles: UserRole[];
  defaultRole: UserRole;
  permissibleQueries: PermissibleQueriesMap | null;
  rolePermissions: RolePermissionsMap | null;
};

export type ParsedUserToken = {
  exp: number;
  'https://hasura.io/jwt/claims': {
    'x-hasura-allowed-roles': UserRole[];
    'x-hasura-default-role': UserRole;
    'x-hasura-user-id': string;
  };
  iat: number;
  username: string;
};

export type Version = {
  branch: string;
  commit: string;
  commitUrl: string;
  date: string;
  name: string;
};

export type PartialWith<T, K extends keyof T> = Partial<T> & Pick<T, K>;
export type UnionOfValues<T extends Record<string, any>, K extends keyof T> = T[K] extends infer U ? U : never;
