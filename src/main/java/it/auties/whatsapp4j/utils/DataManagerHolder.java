package it.auties.whatsapp4j.utils;

import it.auties.whatsapp4j.manager.WhatsappDataManager;
import it.auties.whatsapp4j.whatsapp.WhatsappAPI;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * @author aobinbin
 * @since 2021/12/20
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DataManagerHolder {

    /*InheritableThreadLocal是ThreadLocal的子类，可以解决父线程和子线程的数据传输问题*/
    public static final ThreadLocal<WhatsappDataManager> DATA_MANAGER = new InheritableThreadLocal<>();

}
