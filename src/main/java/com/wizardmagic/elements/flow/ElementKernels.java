package com.wizardmagic.elements.flow;

import com.wizardmagic.elements.ElementField;

/**
 * 元素输运内核的可替换入口（为将来 JNI / 并行实现留的插槽）。
 *
 * <p><b>契约（CHANGE §7.1 勘误）</b>：实现必须是「同一遍内不读写同一批数据」的确定语义
 * （Jacobi 或红黑 Gauss-Seidel）。现有 {@link ElementDiffusion#stepFlux} 是
 * Gauss-Seidel 式就地更新，结果依赖遍历顺序——移植到 JNI / 多线程之前，
 * 必须先改成 Jacobi 或红黑 GS，否则同一输入在不同线程数下会得到不同结果。
 */
public interface ElementKernels {

    boolean stepFlux(ElementField field, double rate, int maxFlux, double beta);

    /** 默认纯 Java 实现（当前唯一实现）。 */
    static ElementKernels java() {
        return JavaKernels.INSTANCE;
    }

    final class JavaKernels implements ElementKernels {
        static final JavaKernels INSTANCE = new JavaKernels();

        private JavaKernels() {
        }

        @Override
        public boolean stepFlux(ElementField field, double rate, int maxFlux, double beta) {
            return ElementDiffusion.stepFlux(field, rate, maxFlux, beta);
        }
    }
}
