import java.math.BigInteger
import kotlin.random.Random

typealias BI = BigInteger
val SCALE: BI = BI.valueOf(100_000_000L)


fun longToInternal(valueWhole: Long): BI = BI.valueOf(valueWhole) * SCALE

// Класс рационального числа - дроби вида num/znam
data class Rational(val num: BI, val znam: BI) {
    init {
        if (znam == BI.ZERO) {
            throw IllegalArgumentException("Denominator must not be zero")
        }
    }
    // Нормализация дроби
    fun normalized(): Rational {
        val g = num.gcd(znam)
        val n = num.divide(g)
        val d = znam.divide(g)

        return if (d.signum() < 0) {
            Rational(n.negate(), d.negate())
        } else {
            Rational(n, d)
        }
    }
    // Произведение рациональных
    operator fun times(other: Rational): Rational {
        return Rational(num.multiply(other.num), znam.multiply(other.znam)).normalized()
    }
    // Инвертирование рационального числа
    fun invert(): Rational = Rational(znam, num)

    // Домножение BigInteger на Rational
    fun applyTo(amount: BI): BI {
        val res = amount.multiply(num).divide(znam)
        return res
    }
    companion object {
        fun of(n: Long, d: Long = 1L) = Rational(BI.valueOf(n), BI.valueOf(d)).normalized()
    }
}


enum class Currency(val code: String) {
    USD("USD"),
    EUR("EUR"),
    RUB("RUB"),
    BTC("BTC"),
    ETH("ETH");

    override fun toString(): String = code

    companion object {
        fun fromCode(code: String): Currency? =
            entries.firstOrNull {it.code.equals(code, ignoreCase = true)}
    }
}


// Интерфейс биржи
interface Exchange {
    fun getRate(from: Currency, to: Currency): Rational?
    fun convert(amount: BI, from: Currency, to: Currency): Result<BI>
    fun randomizeRates()
    fun showRates(): String
}


// Реализация интерфейса биржи
class ConsoleExchange(initRates: Map<Pair<Currency, Currency>, Rational>) : Exchange {
    // Map from (from, to) -> Rational хранит явно заданные валютные пары {(currency, currency), rational}
    private val rates: MutableMap<Pair<Currency, Currency>, Rational> = mutableMapOf()
    // Adjacency from -> (to -> rate) графовое представление связей между парами валют(заданных явно)
    // Currency -> MutableMap смежных с ней(и значение в ребрах)
    private val adjacency: MutableMap<Currency, MutableMap<Currency, Rational>> = mutableMapOf()

    // Установка новой пары в adjacency и rates (синхронизирующая adjacency и rates)
    private fun setRate(pair: Pair<Currency, Currency>, rate: Rational) {
        val normalized = rate.normalized()
        rates[pair] = normalized
        adjacency.computeIfAbsent(pair.first) { mutableMapOf() }[pair.second] = normalized
    }

    init {
        // Установка стандартных валютных пар
        rates.putAll(initRates.mapValues { it.value.normalized() })
        for ((pair, rate) in rates) {
            val (a, b) = pair
            adjacency.computeIfAbsent(a) { mutableMapOf() }[b] = rate
        }
    }

    //Получение курса между валютами from и to
    override fun getRate(from: Currency, to: Currency): Rational? {
        //corner case - валютная пара задана при инициализации или to=from(курс обмена = 1)
        if (from == to) return Rational.of(1, 1)
        rates[Pair(from, to)]?.let { return it }

        //BFS для поиска пути между парами from и to и произведение курсов по этому пути
        val queue = ArrayDeque<Currency>()
        val prev = mutableMapOf<Currency, Currency?>()
        queue.add(from)
        prev[from] = null

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val neighbors = adjacency[current] ?: continue
            for ((neighbor, _) in neighbors) {
                if (neighbor in prev) continue
                prev[neighbor] = current
                if (neighbor == to) {
                    // Восстановление пути
                    val path = mutableListOf<Currency>()
                    var x: Currency? = to
                    while (x != null) {
                        path.add(x)
                        x = prev[x]
                    }
                    path.reverse()
                    // Произведение курсов по пути из from в to
                    var acc = Rational.of(1, 1)
                    for (i in 0 until path.size - 1) {
                        val first = path[i]
                        val second = path[i + 1]
                        val rate = adjacency[first]?.get(second) ?: return null
                        acc *= rate
                    }
                    return acc.normalized()
                }
                queue.add(neighbor)
            }
        }
        return null
    }

    // Конвертация amount единиц валюты из from в to
    // Возвращает Result<BI>
    override fun convert(amount: BI, from: Currency, to: Currency): Result<BI> {
        if (amount <= BI.ZERO) return Result.failure(IllegalArgumentException("Amount must be positive"))
        val rate = getRate(from, to) ?: return Result.failure(IllegalStateException("No conversion path from $from to $to"))
        val converted = rate.applyTo(amount)
        return Result.success(converted)
    }

    // Случайное изменение курсов в пределах ±5%
    override fun randomizeRates() {
        val maxPct = 5 // ±5%
        val snapshot = rates.toMap()
        snapshot.forEach { (k, v) ->
            val delta = Random.nextInt(-maxPct, maxPct + 1)
            val multiplier = Rational.of((100 + delta).toLong(), 100) // (100+delta)/100
            val newRate = (v * multiplier).normalized()
            //Установка нового курса по валютной паре k и обратного
            if (newRate.num != BI.ZERO && newRate.znam != BI.ZERO) {
                setRate(k, newRate)
                val invKey = Pair(k.second, k.first)
                setRate(invKey, newRate.invert().normalized())
            }
        }
    }

    // Вывод курсов валют
    override fun showRates(): String {
        val sb = StringBuilder()
        rates.entries.sortedBy { it.key.first.code + it.key.second.code }.forEach { (k, v) ->
            sb.append("${k.first} -> ${k.second} : ${formatRational(v)}\n")
        }
        return sb.toString()
    }

    // представить рациональное число r как десятичное с SCALE (8 знаков)
    private fun formatRational(r: Rational): String {
        //r = (num * SCALE)/znam / SCALE
        val scaled = r.num.multiply(SCALE).divide(r.znam)
        val whole = scaled.divide(SCALE) // целое
        val frac = scaled.mod(SCALE).abs() // дробное
        return "${whole}.${frac.toString().padStart(8, '0')}"
    }

    // если в rates есть пара [currency1, currency2], то также посчитать курс для [currency2, currency1]
    fun ensureBothDirections() {
        val snapshot = rates.toMap()
        snapshot.forEach { (k, v) ->
            val invKey = Pair(k.second, k.first)
            val inv = v.invert().normalized()
            setRate(invKey, inv)
        }
    }
}

// Класс пользователя
class User(val name: String) {
    // MutableMap баланса пользователя в каждой из инициализированных валют
    private val balances: MutableMap<Currency, BI> = mutableMapOf()

    // Функция пополнения баланса currency на сумму amount
    fun deposit(currency: Currency, amount: BI) {
        balances[currency] = balances.getOrDefault(currency, BI.ZERO).add(amount)
    }

    // Функция снятия с баланса currency суммы amount
    fun withdraw(currency: Currency, amount: BI): Boolean {
        val cur = balances.getOrDefault(currency, BI.ZERO)
        return if (cur >= amount) {
            balances[currency] = cur.subtract(amount)
            true
        } else false
    }

    // Вывести баланс в валюте currency
    fun getBalance(currency: Currency): BI = balances.getOrDefault(currency, BI.ZERO)
}

// Визуальное представление Big Integer умноженного на SCALE как числа с плавающей точкой
// (например)BI -> 12.34
fun formatAmount(bi: BI): String {
    val whole = bi.divide(SCALE)
    val frac = bi.mod(SCALE).abs().toString().padStart(8, '0')
    return "$whole.$frac"
}


fun main() {
    val initial = mapOf(
        Pair(Currency.USD, Currency.EUR) to Rational.of(9_100_0000L, 10_000_0000L),
        Pair(Currency.USD, Currency.RUB) to Rational.of(75,1),
        Pair(Currency.USD, Currency.BTC) to Rational.of(1, 50_000L),
        Pair(Currency.BTC, Currency.ETH) to Rational.of(13,1)
    ).mapValues { it.value.normalized() }

    val exchange = ConsoleExchange(initial)
    exchange.ensureBothDirections()

    println("=== Мини-обменник (консоль) ===")
    println("Поддерживаемые валюты: ${Currency.entries.joinToString { it.code }}")

    print("Введите имя для регистрации: ")
    val input = readlnOrNull()?.trim()
    val name = if (!input.isNullOrBlank()) input else "guest"
    val user = User(name)
    val starting = longToInternal(1000)
    user.deposit(Currency.USD, starting)
    println("Пользователь ${user.name} зарегистрирован и получил ${formatAmount(starting)} USD")

    loop@ while (true) {
        println("\n--- Меню ---")
        println("1) Показать курсы")
        println("2) Показать балансы")
        println("3) Обменять валюту")
        println("4) Randomize rates (для теста)")
        println("5) Выход")
        print("Выберите опцию: ")
        val opt = readlnOrNull()?.trim()
        when (opt) {
            "1" -> {
                println("--- Текущие курсы (внутренняя форма, 8 знаков) ---")
                println(exchange.showRates())
            }
            "2" -> {
                println("--- Балансы ---")
                Currency.entries.forEach { c ->
                    val b = user.getBalance(c)
                    if (b > BI.ZERO) println("${c}: ${formatAmount(b)}")
                }
            }
            "3" -> {
                print("Откуда (код валюты): ")
                val fromCode = readlnOrNull()?.trim() ?: ""
                val from = Currency.fromCode(fromCode)
                if (from == null) {
                    println("Неизвестная валюта")
                    continue@loop
                }
                print("Куда (код валюты): ")
                val toCode = readlnOrNull()?.trim() ?: ""
                val to = Currency.fromCode(toCode)
                if (to == null) {
                    println("Неизвестная валюта")
                    continue@loop
                }
                print("Сумма (например 12.34): ")
                // Считывание числа с плавающей точкой и перевод в BI
                val amtInput = readlnOrNull()?.trim()
                val amount = try {
                    val parts = amtInput?.split('.') ?: listOf("0")
                    val whole = parts.getOrElse(0) { "0" }.toLong()
                    val fracStr = parts.getOrElse(1) { "0" }.padEnd(8, '0').take(8)
                    val fracBI = BI(fracStr)
                    BI.valueOf(whole) * SCALE + fracBI
                } catch (e: Exception) {
                    println("Неправильная сумма")
                    continue@loop
                }
                val have = user.getBalance(from)
                if (have < amount) {
                    println("Недостаточно средств: у вас ${formatAmount(have)} $from")
                    continue@loop
                }
                val convRes = exchange.convert(amount, from, to)
                if (convRes.isFailure) {
                    println("Не удалось конвертировать: ${convRes.exceptionOrNull()?.message}")
                    continue@loop
                }
                val got = convRes.getOrThrow()
                val withdrawn = user.withdraw(from, amount)
                if (!withdrawn) {
                    println("Ошибка списания")
                    continue@loop
                }
                user.deposit(to, got)
                println("Успешно: списано ${formatAmount(amount)} $from, получено ${formatAmount(got)} $to")
            }
            "4" -> {
                exchange.randomizeRates()
                println("Курсы обновлены (рандомно).")
            }
            "5" -> {
                println("Bye.")
                break@loop
            }
            else -> println("Неверная опция")
        }
        exchange.randomizeRates()
    }
}
