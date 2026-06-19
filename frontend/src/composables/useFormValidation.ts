// 轻量表单校验组合式函数。无第三方依赖，配合 form/ 原子组件使用。
//
// 用法：
//   const { rules, fieldError, validateField, validateAll, isValid } = useFormValidation(
//     () => ({ name: form.name, port: form.port }),
//     {
//       name: [rules.required('请填写名称')],
//       port: [rules.required(), rules.port()],
//     },
//   );
//   // 失焦时：@blur="validateField('port')"
//   // 提交时：if (!validateAll()) return;
//   // 字段错误：:error="fieldError('port')"
//   // 提交按钮：:disabled="!isValid"

import { computed, reactive } from 'vue';

/** 校验规则：返回 null 表示通过，返回字符串表示错误信息。 */
export type ValidatorRule = (value: any) => string | null;

/** 内置规则集合。每个工厂都接受可选的自定义错误信息。 */
export const rules = {
  required(msg = '此项必填'): ValidatorRule {
    return (v) => {
      if (v === null || v === undefined) return msg;
      if (typeof v === 'string' && v.trim() === '') return msg;
      if (Array.isArray(v) && v.length === 0) return msg;
      return null;
    };
  },

  /** 数值最小值（含）。空值跳过，交给 required 处理。 */
  min(n: number, msg?: string): ValidatorRule {
    return (v) => {
      if (v === '' || v === null || v === undefined) return null;
      return Number(v) < n ? (msg ?? `不能小于 ${n}`) : null;
    };
  },

  /** 数值最大值（含）。 */
  max(n: number, msg?: string): ValidatorRule {
    return (v) => {
      if (v === '' || v === null || v === undefined) return null;
      return Number(v) > n ? (msg ?? `不能大于 ${n}`) : null;
    };
  },

  /** 数值区间（含两端）。 */
  range(lo: number, hi: number, msg?: string): ValidatorRule {
    return (v) => {
      if (v === '' || v === null || v === undefined) return null;
      const n = Number(v);
      return n < lo || n > hi ? (msg ?? `应在 ${lo} ~ ${hi} 之间`) : null;
    };
  },

  /** 字符串长度上限。 */
  maxLength(n: number, msg?: string): ValidatorRule {
    return (v) => {
      if (v === null || v === undefined) return null;
      return String(v).length > n ? (msg ?? `不超过 ${n} 个字符`) : null;
    };
  },

  /** 端口号 1 ~ 65535。 */
  port(msg = '端口需在 1 ~ 65535 之间'): ValidatorRule {
    return (v) => {
      if (v === '' || v === null || v === undefined) return null;
      const n = Number(v);
      return !Number.isInteger(n) || n < 1 || n > 65535 ? msg : null;
    };
  },

  /** URL 格式（http/https）。 */
  url(msg = '请填写合法的 URL'): ValidatorRule {
    return (v) => {
      if (v === '' || v === null || v === undefined) return null;
      try {
        const u = new URL(String(v));
        return u.protocol === 'http:' || u.protocol === 'https:' ? null : msg;
      } catch {
        return msg;
      }
    };
  },

  /** 正则匹配。 */
  pattern(re: RegExp, msg = '格式不正确'): ValidatorRule {
    return (v) => {
      if (v === '' || v === null || v === undefined) return null;
      return re.test(String(v)) ? null : msg;
    };
  },

  /** 自定义规则的语法糖（直接传入函数）。 */
  custom(fn: ValidatorRule): ValidatorRule {
    return fn;
  },
};

type RuleMap = Record<string, ValidatorRule[]>;

export function useFormValidation(
  getValues: () => Record<string, any>,
  ruleMap: RuleMap,
) {
  // 已经被校验过（失焦或提交）的字段才显示错误，避免一进表单满屏红字。
  const errors = reactive<Record<string, string | null>>({});
  const touched = reactive<Record<string, boolean>>({});

  const runRules = (field: string, value: any): string | null => {
    const fieldRules = ruleMap[field] || [];
    for (const rule of fieldRules) {
      const err = rule(value);
      if (err) return err;
    }
    return null;
  };

  /** 校验单个字段（通常在 blur 时调用），并标记为已触碰。 */
  const validateField = (field: string): boolean => {
    touched[field] = true;
    const values = getValues();
    errors[field] = runRules(field, values[field]);
    return !errors[field];
  };

  /** 校验全部字段（提交时调用）。任一不过返回 false。 */
  const validateAll = (): boolean => {
    const values = getValues();
    let ok = true;
    for (const field of Object.keys(ruleMap)) {
      touched[field] = true;
      const err = runRules(field, values[field]);
      errors[field] = err;
      if (err) ok = false;
    }
    return ok;
  };

  /** 取某字段当前应展示的错误（未触碰过则不展示）。 */
  const fieldError = (field: string): string => {
    return touched[field] ? (errors[field] || '') : '';
  };

  /** 清空校验状态（如关闭弹窗/重置表单时）。 */
  const resetValidation = () => {
    for (const k of Object.keys(errors)) errors[k] = null;
    for (const k of Object.keys(touched)) touched[k] = false;
  };

  /** 整个表单是否有效——不依赖 touched，实时反映当前值，供提交按钮禁用用。 */
  const isValid = computed(() => {
    const values = getValues();
    for (const field of Object.keys(ruleMap)) {
      if (runRules(field, values[field])) return false;
    }
    return true;
  });

  return { rules, fieldError, validateField, validateAll, resetValidation, isValid };
}
