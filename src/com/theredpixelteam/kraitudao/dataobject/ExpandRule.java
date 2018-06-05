package com.theredpixelteam.kraitudao.dataobject;

import com.theredpixelteam.kraitudao.annotations.expandable.Source;

/**
 * 版权所有（C） The Red Pixel <theredpixelteam.com>
 * 版权所有（C)  KuCrO3 Studio
 * 这一程序是自由软件，你可以遵照自由软件基金会出版的GNU通用公共许可证条款
 * 来修改和重新发布这一程序。或者用许可证的第二版，或者（根据你的选择）用任
 * 何更新的版本。
 * 发布这一程序的目的是希望它有用，但没有任何担保。甚至没有适合特定目的的隐
 * 含的担保。更详细的情况请参阅GNU通用公共许可证。
 */
public interface ExpandRule {
    public Class<?> getExpandingType();

    public Entry[] getEntries();

    public interface Entry extends Metadatable {
        public String name();

        public At getterInfo();

        public At setterInfo();

        public Class<?> getExpandedType();
    }

    public interface At {
        public String name();

        public Source source();
    }
}
