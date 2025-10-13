import java.math.BigInteger
import kotlin.random.Random

typealias BI = BigInteger
val SCALE: BI = BI.valueOf(100_000_000L)


fun longToInternal(valueWhole: Long): BI = BI.valueOf(valueWhole) * SCALE

data class Rational(val num: BI, val znam: BI) {
    init {
        if (znam == BI.ZERO) {
            throw IllegalArgumentException("Denominator must not be zero")
        }
    }
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
    operator fun times(other: Rational): Rational {
        return Rational(num.multiply(other.num), znam.multiply(other.znam)).normalized()
    }
    fun invert(): Rational = Rational(znam, num)

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


interface Exchange {
    fun getRate(from: Currency, to: Currency): Rational?
    fun convert(amount: BI, from: Currency, to: Currency): Result<BI>
    fun randomizeRates()
    fun showRates(): String
}


class ConsoleExchange(initRates: Map<Pair<Currency, Currency>, Rational>) : Exchange {
    private val rates: MutableMap<Pair<Currency, Currency>, Rational> = mutableMapOf()
    private val adj: MutableMap<Currency, MutableMap<Currency, Rational>> = mutableMapOf()

    private fun setRate(pair: Pair<Currency, Currency>, rate: Rational) {
        val normalized = rate.normalized()
        rates[pair] = normalized
        adj.computeIfAbsent(pair.first) { mutableMapOf() }[pair.second] = normalized
    }

    init {
        rates.putAll(initRates.mapValues { it.value.normalized() })
        for ((pair, rate) in rates) {
            val (a, b) = pair
            adj.computeIfAbsent(a) { mutableMapOf() }[b] = rate
        }
    }

    override fun getRate(from: Currency, to: Currency): Rational? {
        if (from == to) return Rational.of(1, 1)
        rates[Pair(from, to)]?.let { return it }

        //bfs
        val q = ArrayDeque<Currency>()
        val prev = mutableMapOf<Currency, Currency?>()
        q.add(from)
        prev[from] = null
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            val neighbors = adj[cur] ?: continue
            for ((nb, _) in neighbors) {
                if (nb in prev) continue
                prev[nb] = cur
                if (nb == to) {
                    val path = mutableListOf<Currency>()
                    var x: Currency? = to
                    while (x != null) {
                        path.add(x)
                        x = prev[x]
                    }
                    path.reverse()
                    var acc = Rational.of(1, 1)
                    for (i in 0 until path.size - 1) {
                        val a = path[i]
                        val b = path[i + 1]
                        val r = adj[a]?.get(b) ?: return null
                        acc *= r
                    }
                    return acc.normalized()
                }
                q.add(nb)
            }
        }
        return null
    }

    override fun convert(amount: BI, from: Currency, to: Currency): Result<BI> {
        if (amount <= BI.ZERO) return Result.failure(IllegalArgumentException("Amount must be positive"))
        val rate = getRate(from, to) ?: return Result.failure(IllegalStateException("No conversion path from $from to $to"))
        val converted = rate.applyTo(amount)
        return Result.success(converted)
    }

    override fun randomizeRates() {
        val maxPct = 5 // ±5%
        val snapshot = rates.toMap()
        snapshot.forEach { (k, v) ->
            val delta = Random.nextInt(-maxPct, maxPct + 1)
            val multiplier = Rational.of((100 + delta).toLong(), 100) // (100+delta)/100
            val newRate = (v * multiplier).normalized()
            if (newRate.num != BI.ZERO && newRate.znam != BI.ZERO) {
                setRate(k, newRate)
                val invKey = Pair(k.second, k.first)
                setRate(invKey, newRate.invert().normalized())
            }
        }
    }

    override fun showRates(): String {
        val sb = StringBuilder()
        rates.entries.sortedBy { it.key.first.code + it.key.second.code }.forEach { (k, v) ->
            sb.append("${k.first} -> ${k.second} : ${formatRational(v)}\n")
        }
        return sb.toString()
    }

    private fun formatRational(r: Rational): String {
        //r = (num * SCALE)/znam / SCALE
        val scaled = r.num.multiply(SCALE).divide(r.znam)
        val whole = scaled.divide(SCALE)
        val frac = scaled.mod(SCALE).abs()
        return "${whole}.${frac.toString().padStart(8, '0')}"
    }

    fun ensureBothDirections() {
        val snapshot = rates.toMap()
        snapshot.forEach { (k, v) ->
            val invKey = Pair(k.second, k.first)
            val inv = v.invert().normalized()
            setRate(invKey, inv)
        }
    }
}

class User(val name: String) {
    private val balances: MutableMap<Currency, BI> = mutableMapOf()

    fun deposit(currency: Currency, amount: BI) {
        balances[currency] = balances.getOrDefault(currency, BI.ZERO).add(amount)
    }

    fun withdraw(currency: Currency, amount: BI): Boolean {
        val cur = balances.getOrDefault(currency, BI.ZERO)
        return if (cur >= amount) {
            balances[currency] = cur.subtract(amount)
            true
        } else false
    }

    fun getBalance(currency: Currency): BI = balances.getOrDefault(currency, BI.ZERO)
}


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
